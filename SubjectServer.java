import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;

/**
 * SubjectServer - a TCP server holding the shared list of subjects.
 *
 * Clients connect over TCP and exchange one JSON object per line: one
 * request line in, one response line out. The five operations (QUERY,
 * ENROL, WITHDRAW, TRANSFER, UPDATE_CAPACITY) are dispatched from
 * {@link #process(String)} to a handler each.
 *
 * Each accepted connection is handed to its own thread, which serves that
 * client until it disconnects (thread-per-connection). Shared state is
 * guarded one subject at a time: every Subject carries its own
 * ReentrantReadWriteLock, so QUERYs on a subject run together, a write on
 * a subject excludes everything else on it, and work on different subjects
 * never contends. TRANSFER takes both write locks in ascending subjectCode
 * order so that opposing transfers cannot deadlock.
 *
 * Usage:
 *   java -jar SubjectServer.jar <port> <subject-data-file> <artificial-delay-ms>
 */
public class SubjectServer {

    private static final DateTimeFormatter LOG_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * subjectCode -> Subject. Fully populated before the first handler
     * thread starts and never re-keyed afterwards, so lookups need no lock
     * of their own; Thread.start() publishes it safely to every handler.
     * The mutable state inside each Subject is what the per-subject locks
     * guard.
     */
    private final Map<String, Subject> subjects;

    /** Artificial delay applied to every operation, in milliseconds. */
    private final int delayMs;

    /**
     * Guards every field below it: the live connection set, the in-flight
     * operation count, the connection id counter and the shutdown flag.
     */
    private final Object monitor = new Object();

    private final Set<ConnectionHandler> activeConnections = new LinkedHashSet<>();
    private int inFlightOperations = 0;
    private int nextConnectionId = 1;
    private boolean shuttingDown = false;

    /** The listening socket, closed by the console's quit command. */
    private ServerSocket serverSocket;

    private SubjectServer(Map<String, Subject> subjects, int delayMs) {
        this.subjects = subjects;
        this.delayMs = delayMs;
    }

    public static void main(String[] args) {

        // ==================== Argument parsing (provided) ====================
        if (args.length != 3) {
            System.err.println("Usage: java -jar SubjectServer.jar <port> <subject-data-file> <artificial-delay-ms>");
            System.exit(1);
            return;
        }

        int port = -1;
        try {
            port = Integer.parseInt(args[0]);
            if (port < 0 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Invalid port '" + args[0] + "': must be an integer between 0 and 65535.");
            System.exit(1);
            return;
        }

        String subjectDataFile = args[1];

        int delayMs = -1;
        try {
            delayMs = Integer.parseInt(args[2]);
            if (delayMs < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Invalid artificial delay '" + args[2] + "': must be a non-negative integer, in milliseconds.");
            System.exit(1);
            return;
        }

        // ==================== Load the subject data file (partly provided) ====================
        Map<String, Subject> subjects = loadSubjects(subjectDataFile);
        System.out.println("Loaded " + subjects.size() + " subject(s) from " + subjectDataFile);

        new SubjectServer(subjects, delayMs).run(port);
    }

    // ====================================================================
    // Networking
    // ====================================================================

    /** Binds the listening socket and serves connections until it is closed. */
    private void run(int port) {
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            System.err.println("Could not listen on port " + port + ": " + e.getMessage());
            System.exit(1);
            return; // unreachable, System.exit terminates the JVM
        }

        System.out.println("Listening on port " + serverSocket.getLocalPort()
                + " with an artificial delay of " + delayMs + " ms per operation.");
        System.out.println("Console commands: status | quit");

        Thread console = new Thread(this::runConsole, "console");
        console.setDaemon(true);
        console.start();

        acceptConnections();
    }

    /**
     * Accepts connections until the listening socket is closed, giving each
     * one its own thread so that slow operations on one connection never
     * hold up another.
     */
    private void acceptConnections() {
        while (true) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (isShuttingDown()) return; // quit closed the listening socket
                System.err.println("Stopped accepting connections: " + e.getMessage());
                return;
            }

            ConnectionHandler handler;
            synchronized (monitor) {
                if (shuttingDown) {
                    closeQuietly(socket);
                    return;
                }
                handler = new ConnectionHandler(socket, nextConnectionId++);
                activeConnections.add(handler);
            }

            Thread thread = new Thread(handler, "client-" + handler.id);
            handler.thread = thread;
            thread.start();
        }
    }

    /**
     * Serves one client for the lifetime of its connection: read a request
     * line, write a response line, repeat until the client disconnects.
     *
     * A bad request is answered and the connection kept open; only an I/O
     * failure, the client hanging up, or server shutdown ends the loop.
     */
    private final class ConnectionHandler implements Runnable {

        private final Socket socket;
        private final int id;
        private volatile Thread thread;

        ConnectionHandler(Socket socket, int id) {
            this.socket = socket;
            this.id = id;
        }

        @Override
        public void run() {
            String peer = String.valueOf(socket.getRemoteSocketAddress());
            System.out.println("Client " + id + " connected from " + peer);
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                String line;
                while ((line = in.readLine()) != null) {
                    // Registering the operation first is what lets quit wait
                    // for it: once shutdown has begun, no new one starts.
                    if (!beginOperation()) break;
                    try {
                        ProtocolMessage response = process(line);
                        out.write(response.toJson());
                        out.write("\n");
                        out.flush();
                    } finally {
                        endOperation();
                    }
                }
            } catch (IOException e) {
                if (!isShuttingDown()) {
                    System.err.println("Connection to " + peer + " failed: " + e.getMessage());
                }
            } finally {
                closeQuietly(socket);
                synchronized (monitor) {
                    activeConnections.remove(this);
                }
                System.out.println("Client " + id + " disconnected");
            }
        }

        /** Closes this connection, unblocking a handler waiting on readLine. */
        void close() {
            closeQuietly(socket);
        }
    }

    /**
     * Claims a slot for one operation, or returns false if the server is
     * shutting down and the handler should stop reading requests.
     */
    private boolean beginOperation() {
        synchronized (monitor) {
            if (shuttingDown) return false;
            inFlightOperations++;
            return true;
        }
    }

    private void endOperation() {
        synchronized (monitor) {
            inFlightOperations--;
            monitor.notifyAll();
        }
    }

    private boolean isShuttingDown() {
        synchronized (monitor) {
            return shuttingDown;
        }
    }

    // ====================================================================
    // Server console
    // ====================================================================

    /**
     * Reads console commands from standard input. End of input simply ends
     * the console; the server keeps serving clients, since a server started
     * without a terminal attached should not shut itself down.
     */
    private void runConsole() {
        BufferedReader stdin = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = stdin.readLine()) != null) {
                String command = line.trim().toLowerCase();
                if (command.isEmpty()) continue;

                if (command.equals("status")) {
                    printStatus();
                } else if (command.equals("quit") || command.equals("stop")) {
                    shutdown();
                    return;
                } else {
                    System.out.println("Unknown command '" + command + "'. Try: status | quit");
                }
            }
        } catch (IOException e) {
            System.err.println("Console input closed: " + e.getMessage());
        }
    }

    private void printStatus() {
        synchronized (monitor) {
            System.out.println(activeConnections.size() + " active client connection(s), "
                    + inFlightOperations + " operation(s) in flight.");
        }
    }

    /**
     * Stops accepting new connections, waits for the operations already
     * under way to finish answering their clients, then closes the
     * remaining connections and exits.
     */
    private void shutdown() {
        System.out.println("Shutting down; no new connections will be accepted.");

        List<ConnectionHandler> remaining;
        synchronized (monitor) {
            shuttingDown = true;
        }
        closeQuietly(serverSocket); // unblocks accept()

        synchronized (monitor) {
            while (inFlightOperations > 0) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            remaining = new ArrayList<>(activeConnections);
        }

        for (ConnectionHandler handler : remaining) {
            handler.close();
        }
        for (ConnectionHandler handler : remaining) {
            Thread thread = handler.thread;
            if (thread == null) continue;
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // TODO: persist final state here once persistence is implemented.

        System.out.println("Server stopped.");
        System.exit(0);
    }

    /**
     * Turns one request line into the response to send back. Never throws:
     * a malformed request becomes INVALID_REQUEST and anything unexpected
     * becomes ERROR, so that the connection survives either way.
     */
    private ProtocolMessage process(String line) {
        String op = "-";
        String subjectCode = "-";
        ProtocolMessage response;

        try {
            ProtocolMessage request = ProtocolMessage.parse(line);
            // requireString rather than getOp so a missing "op" is reported
            // as INVALID_REQUEST instead of reaching the switch as null.
            op = request.requireString("op");

            switch (op) {
                case ProtocolMessage.OP_QUERY:
                    subjectCode = request.requireString("subjectCode");
                    response = query(subjectCode);
                    break;

                case ProtocolMessage.OP_ENROL:
                    subjectCode = request.requireString("subjectCode");
                    response = enrol(subjectCode, request.requireString("studentId"));
                    break;

                case ProtocolMessage.OP_WITHDRAW:
                    subjectCode = request.requireString("subjectCode");
                    response = withdraw(subjectCode, request.requireString("studentId"));
                    break;

                case ProtocolMessage.OP_TRANSFER: {
                    String fromSubjectCode = request.requireString("fromSubjectCode");
                    String toSubjectCode = request.requireString("toSubjectCode");
                    subjectCode = fromSubjectCode + "->" + toSubjectCode;
                    response = transfer(fromSubjectCode, toSubjectCode, request.requireString("studentId"));
                    break;
                }

                case ProtocolMessage.OP_UPDATE_CAPACITY:
                    subjectCode = request.requireString("subjectCode");
                    response = updateCapacity(subjectCode, request.requireInt("newCapacity"));
                    break;

                default:
                    response = ProtocolMessage.errorResponse(
                            ProtocolMessage.STATUS_INVALID_REQUEST, "Unknown op: " + op);
            }
        } catch (ProtocolException e) {
            response = ProtocolMessage.errorResponse(
                    ProtocolMessage.STATUS_INVALID_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            response = ProtocolMessage.errorResponse(
                    ProtocolMessage.STATUS_ERROR, "Server error: " + e);
        }

        log(op, subjectCode, response.getStatus());
        return response;
    }

    // ====================================================================
    // Operations
    // ====================================================================

    private ProtocolMessage query(String subjectCode) {
        Subject subject = subjects.get(subjectCode);
        if (subject == null) return notFound(subjectCode);

        Lock readLock = subject.getLock().readLock();
        readLock.lock();
        try {
            pause();
            return ProtocolMessage.successResponse(ProtocolMessage.queryData(
                    subject.getSubjectCode(),
                    subject.getCapacity(),
                    subject.getEnrolledCount(),
                    new ArrayList<>(subject.getEnrolledStudentIds())));
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Checking for a free seat and taking it happen under the same write
     * lock, so two clients racing for a single remaining seat cannot both
     * succeed: the loser sees the updated count and gets FULL.
     */
    private ProtocolMessage enrol(String subjectCode, String studentId) {
        Subject subject = subjects.get(subjectCode);
        if (subject == null) return notFound(subjectCode);

        Lock writeLock = subject.getLock().writeLock();
        writeLock.lock();
        try {
            pause();

            if (subject.getEnrolledStudentIds().contains(studentId)) {
                return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_DUPLICATE_ENROLMENT,
                        "Student " + studentId + " is already enrolled in " + subjectCode + ".");
            }
            if (subject.getEnrolledCount() >= subject.getCapacity()) {
                return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_FULL,
                        subjectCode + " is at capacity (" + subject.getCapacity() + ").");
            }

            subject.getEnrolledStudentIds().add(studentId);
            return ProtocolMessage.successResponse();
        } finally {
            writeLock.unlock();
        }
    }

    private ProtocolMessage withdraw(String subjectCode, String studentId) {
        Subject subject = subjects.get(subjectCode);
        if (subject == null) return notFound(subjectCode);

        Lock writeLock = subject.getLock().writeLock();
        writeLock.lock();
        try {
            pause();

            if (!subject.getEnrolledStudentIds().remove(studentId)) {
                return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_NOT_ENROLLED,
                        "Student " + studentId + " is not enrolled in " + subjectCode + ".");
            }
            return ProtocolMessage.successResponse();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Moves a student from one subject to another under both subjects' write
     * locks, so no other client can observe the student in both subjects or
     * in neither.
     *
     * The two locks are taken in ascending subjectCode order rather than
     * source-then-destination order. Two clients transferring between the
     * same pair of subjects in opposite directions therefore queue for the
     * same lock first instead of each holding the one the other needs.
     *
     * The checks below run in the order the specification mandates:
     * NOT_FOUND, INVALID_REQUEST, NOT_ENROLLED, DUPLICATE_ENROLMENT, FULL.
     */
    private ProtocolMessage transfer(String fromSubjectCode, String toSubjectCode, String studentId) {
        Subject from = subjects.get(fromSubjectCode);
        if (from == null) return notFound(fromSubjectCode);
        Subject to = subjects.get(toSubjectCode);
        if (to == null) return notFound(toSubjectCode);

        if (fromSubjectCode.equals(toSubjectCode)) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_INVALID_REQUEST,
                    "Cannot transfer a student from " + fromSubjectCode + " to itself.");
        }

        // Consistent global lock order: lower subjectCode first, whichever
        // end of the transfer it happens to be.
        boolean fromIsFirst = fromSubjectCode.compareTo(toSubjectCode) < 0;
        Subject firstLocked = fromIsFirst ? from : to;
        Subject secondLocked = fromIsFirst ? to : from;

        Lock firstLock = firstLocked.getLock().writeLock();
        Lock secondLock = secondLocked.getLock().writeLock();

        firstLock.lock();
        try {
            secondLock.lock();
            try {
                pause();

                if (!from.getEnrolledStudentIds().contains(studentId)) {
                    return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_NOT_ENROLLED,
                            "Student " + studentId + " is not enrolled in " + fromSubjectCode + ".");
                }
                if (to.getEnrolledStudentIds().contains(studentId)) {
                    return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_DUPLICATE_ENROLMENT,
                            "Student " + studentId + " is already enrolled in " + toSubjectCode + ".");
                }
                if (to.getEnrolledCount() >= to.getCapacity()) {
                    return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_FULL,
                            toSubjectCode + " is at capacity (" + to.getCapacity() + ").");
                }

                from.getEnrolledStudentIds().remove(studentId);
                to.getEnrolledStudentIds().add(studentId);
                return ProtocolMessage.successResponse();
            } finally {
                secondLock.unlock();
            }
        } finally {
            firstLock.unlock();
        }
    }

    private ProtocolMessage updateCapacity(String subjectCode, int newCapacity) {
        Subject subject = subjects.get(subjectCode);
        if (subject == null) return notFound(subjectCode);

        Lock writeLock = subject.getLock().writeLock();
        writeLock.lock();
        try {
            pause();

            if (newCapacity <= 0) {
                return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_INVALID_REQUEST,
                        "newCapacity must be a positive integer (got " + newCapacity + ").");
            }
            if (newCapacity < subject.getEnrolledCount()) {
                return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_INVALID_REQUEST,
                        "newCapacity " + newCapacity + " is below the current enrolment of "
                                + subject.getEnrolledCount() + " in " + subjectCode + ".");
            }

            subject.setCapacity(newCapacity);
            return ProtocolMessage.successResponse();
        } finally {
            writeLock.unlock();
        }
    }

    private static ProtocolMessage notFound(String subjectCode) {
        return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_NOT_FOUND,
                "No such subject: " + subjectCode + ".");
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * The mandated artificial delay. Every operation calls this after
     * acquiring its lock(s) and before touching any subject state, so the
     * delay always elapses with the locks held. That is what makes
     * contention observable: an operation that has to wait for a lock is
     * measurably delayed rather than overlapping in the same window. The
     * response is written by the connection handler, after process() has
     * returned and the locks have been released.
     */
    private void pause() {
        if (delayMs <= 0) return;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One operational log line per completed operation. */
    private static void log(String op, String subjectCode, String status) {
        System.out.println(LocalDateTime.now().format(LOG_TIMESTAMP)
                + " [" + Thread.currentThread().getName() + "] "
                + op + " " + subjectCode + " -> " + status);
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            // Nothing useful to do while tearing a connection down.
        }
    }

    /**
     * Reads the subject data file, decodes it as JSON, and returns a
     * subjectCode -> Subject map. Exits the process with a clear error
     * message (and non-zero status) on any failure, per the Subject Data
     * File Format section.
     */
    private static Map<String, Subject> loadSubjects(String path) {
        String text;
        try {
            text = new String(Files.readAllBytes(Paths.get(path)));
        } catch (IOException e) {
            System.err.println("Could not read subject data file '" + path + "': " + e.getMessage());
            System.exit(1);
            return null; // unreachable, System.exit terminates the JVM
        }

        Object decoded;
        try {
            decoded = SimpleJson.decode(text);
        } catch (SimpleJson.JsonParseException e) {
            System.err.println("Subject data file '" + path + "' is not valid JSON: " + e.getMessage());
            System.exit(1);
            return null; // unreachable
        }

        if (!(decoded instanceof List)) {
            System.err.println("Subject data file '" + path + "' must contain a JSON array at the top level.");
            System.exit(1);
            return null; // unreachable
        }

        Map<String, Subject> subjects = new HashMap<>();

        int index = 0;
        for (Object item : (List<?>) decoded) {
            String where = "Entry " + index + " of subject data file '" + path + "'";
            index++;

            if (!(item instanceof Map)) {
                fail(where + " is not a JSON object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) item;

            // --- subjectCode: present, a non-empty string, unique in the file ---
            Object rawCode = entry.get("subjectCode");
            if (!(rawCode instanceof String) || ((String) rawCode).trim().isEmpty()) {
                fail(where + ": 'subjectCode' must be a non-empty string (got " + describe(rawCode) + ").");
            }
            String subjectCode = (String) rawCode;
            if (subjects.containsKey(subjectCode)) {
                fail(where + ": duplicate subjectCode '" + subjectCode + "'; subject codes must be unique.");
            }

            // --- capacity: present and a positive integer ---
            Object rawCapacity = entry.get("capacity");
            Integer capacityValue = asWholeNumber(rawCapacity);
            if (capacityValue == null || capacityValue <= 0) {
                fail(where + ": 'capacity' must be a positive integer (got " + describe(rawCapacity) + ").");
            }
            int capacity = capacityValue;

            // --- enrolledStudentIds: an array of strings, no longer than capacity ---
            Object rawIds = entry.get("enrolledStudentIds");
            if (!(rawIds instanceof List)) {
                fail(where + ": 'enrolledStudentIds' must be a JSON array (got " + describe(rawIds) + ").");
            }
            List<?> rawIdList = (List<?>) rawIds;
            if (rawIdList.size() > capacity) {
                fail(where + ": 'enrolledStudentIds' has " + rawIdList.size()
                        + " entries, which exceeds the capacity of " + capacity + ".");
            }

            Set<String> enrolledStudentIds = new LinkedHashSet<>();
            for (Object rawId : rawIdList) {
                if (!(rawId instanceof String) || ((String) rawId).trim().isEmpty()) {
                    fail(where + ": every entry in 'enrolledStudentIds' must be a non-empty string (got "
                            + describe(rawId) + ").");
                }
                enrolledStudentIds.add((String) rawId);
            }

            subjects.put(subjectCode, new Subject(subjectCode, capacity, enrolledStudentIds));
        }

        return subjects;
    }

    /**
     * Reports a fatal subject data file problem and terminates the JVM with a
     * non-zero status, as the Subject Data File Format section requires.
     */
    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }

    /**
     * Returns the given decoded JSON value as an Integer if it is a whole
     * number, or null if it is absent or not a whole number. SimpleJson
     * decodes numbers as Long, or Double when they have a fractional part or
     * exponent, so both are considered here (matching ProtocolMessage.getInt).
     */
    private static Integer asWholeNumber(Object value) {
        if (value instanceof Long) {
            long l = (Long) value;
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) return null;
            return (int) l;
        }
        if (value instanceof Double) {
            double d = (Double) value;
            if (d == Math.floor(d) && !Double.isInfinite(d)
                    && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                return (int) d;
            }
        }
        return null;
    }

    /** Renders a decoded JSON value for inclusion in an error message. */
    private static String describe(Object value) {
        if (value == null) return "nothing";
        if (value instanceof String) return "\"" + value + "\"";
        return String.valueOf(value);
    }
}
