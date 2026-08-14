import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SubjectServer - starter skeleton.
 *
 * WHAT'S ALREADY DONE FOR YOU:
 *   - Command-line argument parsing and validation.
 *   - Reading the subject data file and decoding it as JSON.
 *
 * WHAT YOU NEED TO IMPLEMENT (see the TODOs below):
 *   - Validating the decoded subject data against the rules in the
 *     Subject Data File Format section (unique subjectCode, positive
 *     capacity, enrolledStudentIds.length <= capacity), and deciding
 *     what data structure you actually want to hold subjects in for the
 *     rest of the server's life.
 *   - Everything socket-related: opening a ServerSocket, accepting
 *     connections, and handling each one.
 *   - Everything thread-related: your chosen concurrency model
 *     (thread-per-connection, thread-per-request, or a worker pool).
 *   - Your locking strategy for the concurrency requirements (per-subject
 *     read/write locks, the artificial delay placement, and the
 *     lock-ordering needed to make TRANSFER deadlock-free).
 *   - The server console (status / stop commands) and the operational
 *     log.
 *   - Persistence: writing state to disk after every successful write,
 *     and reloading it on restart instead of the original subject data
 *     file.
 *
 * None of that is scaffolded on purpose - it's the actual point of the
 * assignment. Use ProtocolMessage (see its own Javadoc) to build your
 * responses and parse incoming requests; you should not need to touch
 * SimpleJson directly.
 *
 * Usage:
 *   java -jar SubjectServer.jar <port> <subject-data-file> <artificial-delay-ms>
 */
public class SubjectServer {

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

        // TODO: Decide how `subjects` (or whatever structure you replace it
        //       with) will be protected against concurrent access. A plain
        //       HashMap, and the Subject objects inside it, are NOT
        //       thread-safe on their own - see the Concurrency Requirements
        //       section for exactly what's required here.

        // TODO: Open a ServerSocket on `port`.

        // TODO: Start whatever you need (a thread, or a loop on this thread
        //       before/around your accept loop) to read commands from
        //       standard input:
        //         status  -> print the number of active client connections
        //         stop    -> stop accepting new connections, wait for
        //                    in-flight operations to finish, persist final
        //                    state, and exit cleanly
        //       Also print one operational log line per completed
        //       operation (timestamp, handling thread, op, subject code,
        //       resulting status) - format is your choice.

        // TODO: Loop accepting client connections. For each connection,
        //       hand it off according to your chosen concurrency model. For
        //       each request read from a connection:
        //         1. Read one line (one JSON message).
        //         2. Parse it with ProtocolMessage.parse(...), catching
        //            ProtocolException and responding INVALID_REQUEST /
        //            ERROR without closing the connection.
        //         3. Dispatch on request.getOp() to your handling logic for
        //            QUERY / ENROL / WITHDRAW / TRANSFER / UPDATE_CAPACITY,
        //            acquiring the correct lock(s) for that operation,
        //            sleeping for `delayMs` while holding them, performing
        //            the actual read/mutation, then releasing the lock(s)
        //            - see "The artificial delay argument" in the spec for
        //            the exact required ordering.
        //         4. Persist to disk after every successful write.
        //         5. Send back a ProtocolMessage response as one JSON line.

        System.err.println("TODO: SubjectServer networking is not implemented yet - " +
                "see the TODO comments in main().");
    }

    /**
     * Reads the subject data file, decodes it as JSON, and returns a
     * subjectCode -> Subject map. Exits the process with a clear error
     * message (and non-zero status) on any failure, per the Subject Data
     * File Format section.
     *
     * The file reading and JSON decoding is done for you. You still need
     * to fill in the TODO below: validating each entry against the rules
     * the spec requires you to enforce, and constructing your Subject
     * objects from the validated data.
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

        for (Object item : (List<?>) decoded) {
            if (!(item instanceof Map)) {
                System.err.println("Every entry in '" + path + "' must be a JSON object.");
                System.exit(1);
                return null; // unreachable
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) item;

            // TODO: Validate `entry` against the rules in the Subject Data
            //       File Format section, printing a clear message and
            //       calling System.exit(1) if any rule is violated:
            //         - subjectCode must be present, a string, and unique
            //           across the whole file (check against `subjects`,
            //           which already holds everything parsed so far).
            //         - capacity must be present and a positive integer.
            //         - enrolledStudentIds must be present, an array of
            //           strings, with length <= capacity.
            //
            //       Once validated, construct your Subject and add it:
            //         Subject subject = new Subject(subjectCode, capacity, enrolledIds);
            //         subjects.put(subjectCode, subject);
        }

        return subjects;
    }
}
