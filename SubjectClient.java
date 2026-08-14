import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * SubjectClient - starter skeleton.
 *
 * WHAT'S ALREADY DONE FOR YOU:
 *   - Command-line argument parsing.
 *   - The interactive command loop: reading a line from standard input,
 *     splitting it into tokens, checking the argument count for each
 *     command, and printing a usage message on bad input - all without
 *     contacting the server, per the spec.
 *
 * WHAT YOU NEED TO IMPLEMENT (see the TODOs below):
 *   - Opening a socket to the server.
 *   - In each handleXxx method: building the right ProtocolMessage
 *     request, sending it as one JSON line, reading back one line of
 *     response, parsing it with ProtocolMessage.parse(...), and printing
 *     a human-readable summary. The exact wording you print is up to you.
 *   - Closing the connection cleanly on "quit".
 *
 * Usage:
 *   java -jar SubjectClient.jar <server-address> <server-port>
 */
public class SubjectClient {

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

        // TODO: Open a Socket to (serverAddress, serverPort) here, and set
        //       up whatever input/output you'll use to send/receive one
        //       JSON line per request/response. Handle connection failure
        //       (server not reachable, etc.) gracefully.

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
                        // TODO: close your socket/streams here before returning.
                        System.out.println("Goodbye.");
                        return;

                    default:
                        System.out.println("Unrecognised command: " + command);
                        printUsage("query | enrol | withdraw | transfer | update | quit");
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading from standard input: " + e.getMessage());
        }
    }

    private static void printUsage(String usage) {
        System.out.println("Usage: " + usage);
    }

    // ====================================================================
    // TODO: implement each of these. In each one you should:
    //   1. Build the request, e.g. ProtocolMessage.queryRequest(subjectCode)
    //   2. Send request.toJson() followed by a newline to the server, and
    //      flush the stream.
    //   3. Read one line back from the server.
    //   4. Parse it with ProtocolMessage.parse(line).
    //   5. Print a human-readable summary based on the response's status
    //      (and, for QUERY, its data).
    // Handle IOException / ProtocolException here too - a network error or
    // a malformed response from the server should be reported to the user,
    // not crash the client.
    // ====================================================================

    private static void handleQuery(String subjectCode) {
        // TODO
    }

    private static void handleEnrol(String subjectCode, String studentId) {
        // TODO
    }

    private static void handleWithdraw(String subjectCode, String studentId) {
        // TODO
    }

    private static void handleTransfer(String fromSubjectCode, String toSubjectCode, String studentId) {
        // TODO
    }

    private static void handleUpdateCapacity(String subjectCode, String newCapacityStr) {
        int newCapacity;
        try {
            newCapacity = Integer.parseInt(newCapacityStr);
        } catch (NumberFormatException e) {
            System.out.println("newCapacity must be an integer.");
            return;
        }
        // TODO: build ProtocolMessage.updateCapacityRequest(subjectCode, newCapacity),
        //       send it, read the response, and print a summary.
    }
}
