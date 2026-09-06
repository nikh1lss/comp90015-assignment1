import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * SubjectClient - an interactive admin console for the subject server.
 *
 * Connects to the server on startup, then reads commands from standard
 * input one per line. Each recognised command becomes one JSON request
 * line; the reply is read back as one JSON line and summarised for the
 * user. Unrecognised commands are rejected locally without contacting the
 * server.
 *
 * Usage:
 *   java -jar SubjectClient.jar <server-address> <server-port>
 */
public class SubjectClient {

    private static Socket socket;
    private static BufferedReader serverIn;
    private static BufferedWriter serverOut;

    public static void main(String[] args) {

        // ==================== Argument parsing (provided) ====================
        if (args.length != 2) {
            System.err.println("Usage: java -jar SubjectClient.jar <server-address> <server-port>");
            System.exit(1);
            return;
        }

        String serverAddress = args[0];
        int serverPort = -1;
        try {
            serverPort = Integer.parseInt(args[1]);
            if (serverPort < 0 || serverPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Invalid port '" + args[1] + "': must be an integer between 0 and 65535.");
            System.exit(1);
            return;
        }

        // ==================== Connect to the server ====================
        try {
            socket = new Socket(serverAddress, serverPort);
            serverIn = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            serverOut = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Could not connect to " + serverAddress + ":" + serverPort
                    + ": " + e.getMessage());
            System.exit(1);
            return; // unreachable, System.exit terminates the JVM
        }

        System.out.println("Connected to " + serverAddress + ":" + serverPort + ".");
        System.out.println("Type a command: query | enrol | withdraw | transfer | update | quit");

        // ==================== Interactive command loop (provided) ====================
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        try {
            String line;
            while ((line = stdin.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                String[] tokens = trimmed.split("\\s+");
                String command = tokens[0].toLowerCase();

                switch (command) {
                    case "query":
                        if (tokens.length != 2) { printUsage("query <subjectCode>"); break; }
                        handleQuery(tokens[1]);
                        break;

                    case "enrol":
                        if (tokens.length != 3) { printUsage("enrol <subjectCode> <studentId>"); break; }
                        handleEnrol(tokens[1], tokens[2]);
                        break;

                    case "withdraw":
                        if (tokens.length != 3) { printUsage("withdraw <subjectCode> <studentId>"); break; }
                        handleWithdraw(tokens[1], tokens[2]);
                        break;

                    case "transfer":
                        if (tokens.length != 4) { printUsage("transfer <fromSubjectCode> <toSubjectCode> <studentId>"); break; }
                        handleTransfer(tokens[1], tokens[2], tokens[3]);
                        break;

                    case "update":
                        if (tokens.length != 3) { printUsage("update <subjectCode> <newCapacity>"); break; }
                        handleUpdateCapacity(tokens[1], tokens[2]);
                        break;

                    case "quit":
                        System.out.println("Goodbye.");
                        return;

                    default:
                        System.out.println("Unrecognised command: " + command);
                        printUsage("query | enrol | withdraw | transfer | update | quit");
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading from standard input: " + e.getMessage());
        } finally {
            // Covers "quit", end of input, and a failure of standard input.
            disconnect();
        }
    }

    /** Closes the connection to the server, if it is still open. */
    private static void disconnect() {
        closeQuietly(serverOut);
        closeQuietly(serverIn);
        closeQuietly(socket);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException e) {
            // Nothing useful to do while tearing the connection down.
        }
    }

    private static void printUsage(String usage) {
        System.out.println("Usage: " + usage);
    }

    // ====================================================================
    // Commands
    // ====================================================================

    private static void handleQuery(String subjectCode) {
        ProtocolMessage response = exchange(ProtocolMessage.queryRequest(subjectCode));
        if (response == null) return;

        if (!isSuccess(response)) {
            printFailure(response);
            return;
        }

        Map<String, Object> data = response.getData();
        if (data == null) {
            System.out.println("Server returned no data for " + subjectCode + ".");
            return;
        }

        System.out.println(data.get("subjectCode")
                + ": " + data.get("enrolledCount") + "/" + data.get("capacity") + " enrolled");
        Object ids = data.get("enrolledStudentIds");
        if (ids instanceof List && !((List<?>) ids).isEmpty()) {
            for (Object id : (List<?>) ids) {
                System.out.println("  " + id);
            }
        } else {
            System.out.println("  (nobody enrolled)");
        }
    }

    private static void handleEnrol(String subjectCode, String studentId) {
        ProtocolMessage response = exchange(ProtocolMessage.enrolRequest(subjectCode, studentId));
        if (response == null) return;

        if (isSuccess(response)) {
            System.out.println("Enrolled " + studentId + " in " + subjectCode + ".");
        } else {
            printFailure(response);
        }
    }

    private static void handleWithdraw(String subjectCode, String studentId) {
        ProtocolMessage response = exchange(ProtocolMessage.withdrawRequest(subjectCode, studentId));
        if (response == null) return;

        if (isSuccess(response)) {
            System.out.println("Withdrew " + studentId + " from " + subjectCode + ".");
        } else {
            printFailure(response);
        }
    }

    private static void handleTransfer(String fromSubjectCode, String toSubjectCode, String studentId) {
        ProtocolMessage response = exchange(
                ProtocolMessage.transferRequest(fromSubjectCode, toSubjectCode, studentId));
        if (response == null) return;

        if (isSuccess(response)) {
            System.out.println("Transferred " + studentId + " from " + fromSubjectCode
                    + " to " + toSubjectCode + ".");
        } else {
            printFailure(response);
        }
    }

    private static void handleUpdateCapacity(String subjectCode, String newCapacityStr) {
        int newCapacity;
        try {
            newCapacity = Integer.parseInt(newCapacityStr);
        } catch (NumberFormatException e) {
            System.out.println("newCapacity must be an integer.");
            return;
        }

        ProtocolMessage response = exchange(
                ProtocolMessage.updateCapacityRequest(subjectCode, newCapacity));
        if (response == null) return;

        if (isSuccess(response)) {
            System.out.println("Capacity of " + subjectCode + " is now " + newCapacity + ".");
        } else {
            printFailure(response);
        }
    }

    // ====================================================================
    // Talking to the server
    // ====================================================================

    /**
     * Sends one request line and reads one response line back. Returns null
     * if the exchange failed, having already told the user why: a network
     * error, a server that hung up, or a reply that would not parse.
     */
    private static ProtocolMessage exchange(ProtocolMessage request) {
        try {
            serverOut.write(request.toJson());
            serverOut.write("\n");
            serverOut.flush();

            String line = serverIn.readLine();
            if (line == null) {
                System.out.println("The server closed the connection. Type 'quit' to exit.");
                return null;
            }
            return ProtocolMessage.parse(line);
        } catch (IOException e) {
            System.out.println("Could not reach the server: " + e.getMessage());
            return null;
        } catch (ProtocolException e) {
            System.out.println("Unreadable reply from the server: " + e.getMessage());
            return null;
        }
    }

    private static boolean isSuccess(ProtocolMessage response) {
        return ProtocolMessage.STATUS_SUCCESS.equals(response.getStatus());
    }

    /** Prints a non-SUCCESS response as "STATUS: message". */
    private static void printFailure(ProtocolMessage response) {
        String status = response.getStatus();
        String message = response.getString("message");
        if (status == null) {
            System.out.println("Unreadable reply from the server: no status field.");
            return;
        }
        System.out.println(status + (message == null ? "" : ": " + message));
    }
}
