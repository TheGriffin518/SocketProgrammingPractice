import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class myftp {

    private String address;
    private int nport;
    private int tport;
    private int clientId;

    private final Object fileSystemMutex = new Object();

    private BufferedReader commandInput;
    private Worker worker;

    public myftp(String address, int nport, int tport) {

        commandInput = new BufferedReader(new InputStreamReader(System.in));

        this.address = address;
        this.nport = nport;
        this.tport = tport;

        ftp();
    }

    private void ftp() {

        String command;
        worker = new Worker();
        worker.printPrompt();

        // Read until quit breaks loop
        while (true) {
            try {

                // Get user input
                command = commandInput.readLine();
                String[] args = command.split(" ");

                // If input ends with &, spawn a background thread to handle it
                if (args.length > 1 && args[args.length-1].equals("&")) {
                    Worker background = new Worker(command);
                    background.start();
                    if (!args[0].equals("get") && !args[0].equals("put") && !args[0].equals("quit"))
                        worker.printPrompt();

                // Else handle it normally
                } else {

                    switch (args[0].toLowerCase()) {
                        case "get":
                            worker.get(command, args);
                            break;
                        case "put":
                            worker.put(command, args);
                            break;
                        case "cd":
                            worker.cd(command, args);
                            break;
                        case "mkdir":
                            worker.mkdir(command);
                            break;
                        case "terminate":
                            worker.terminate(args);
                            break;
                        case "quit":
                            worker.quit();
                            break;
                        case "":
                            worker.printPrompt();
                            break;
                        default:
                            worker.other(command);
                            break;
                    }
                }

                if (args[0].equalsIgnoreCase("quit")) break;
                if (worker.isClosed()) break;

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Close the connection
        try {
            commandInput.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String args[]) {
        int nport = 5000;
        int tport = 5001;
        if (args.length != 3) {
            System.out.println("FTP Client Error: Expecting 2 argument: An IP Address, a normal port number, and a "
                    + "termination port number");
            System.exit(0);
        } else {
            try {
                nport = Integer.parseInt(args[1]);
                tport = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.out.println("FTPServer Error: Unable to recognize port number.");
                System.exit(0);
            }
        }
        new myftp(args[0], nport, tport);
        System.exit(0);
    }

    /* WORKER THREAD:
     *      The worker class is the main driver for executing ftp commands.
     *      These commands can be executed linearly using explicit calls of the command functions (cd, ls, etc...)
     *      These commands can also be executed as a separate thread using the begin(String command).
     *      If the worker is running as a thread, then the client who spawned this thread is referred to as the
     *      "parent client"
     */
    private class Worker extends Thread implements Runnable {

        private final String prompt = "myftp> ";

        private DataInputStream socket_in;
        private DataOutputStream socket_out;
        private Socket socket;
        private boolean isThread;
        private String command;

        // Connects a worker to the remote server as a unique client
        Worker() {
            isThread = false;
            connect(true);
        }

        // Parses the preset command to run in a separate thread, and then connects to the
        // remote server as non-unique client
        Worker(String command) {
            super();
            if (command.indexOf('&') != -1) {
                command = command.substring(0, command.indexOf('&') - 1);
            }
            isThread = true;
            this.command = command;
            connect(false);
        }

        // Executes the predefined command
        public void run() {

            // Get the parent client's
            getClientDir();

            // If command is not null
            if (command != null) {

                // System.out.println("\t$ Executing background command > " + command);

                // Extract arguments from the command
                String[] args = command.split(" ");

                // Execute command
                try {
                    switch (args[0].toLowerCase()) {
                        case "get":
                            get(command, args);
                            break;
                        case "put":
                            put(command, args);
                            break;
                        case "mkdir":
                            mkdir(command);
                            break;
                        case "cd":
                            cdClient(args);
                            break;
                        case "terminate":
                            terminate(args);
                            break;
                        case "quit":
                            quitClient();
                            break;
                        default:
                            other(command);
                            break;
                    }

                    // Destroy socket with ftpServer after command is finished
                    quit();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /* ============================================= */
        /* ===                                       === */
        /* ===        Command Implementation         === */
        /* ===                                       === */
        /* ============================================= */


        /* ========================= */
        /* ===        get        === */
        /* ========================= */

        private void get(String line, String[] args) throws IOException {

            int downloadSize = 0;

            // 0. Send Command
            socket_out.writeUTF(line);

            try {

                // 1. Get Command ID
                int commandId = socket_in.readInt();
                System.out.println("Command ID : " + commandId);
                if (isThread) worker.printPrompt();

                // 2. Get Status
                int code = socket_in.readInt();
                if (code == 1) {
                    System.out.println("FTP Error: Remote file \"" + args[1] + "\" does not exist.");
                    printPrompt();
                    return;
                }

                // 3. Receive File
                ByteArrayOutputStream fileOut = new ByteArrayOutputStream();
                byte[] buffer = new byte[1000];
                int bufSize;

                waitForResponse();

                int fileSize = socket_in.readInt();
                // System.out.println("fileSize: " + fileSize);

                while (downloadSize < fileSize) {

                    int packetSize = socket_in.readInt();

                    // System.out.println("packetSize: " + packetSize);

                    if (packetSize == 0) {
                        System.out.println("\nGet command " + commandId + " terminated");
                        printPrompt();
                        return;
                    }

                    bufSize = socket_in.read(buffer, 0, buffer.length);

                    fileOut.write(buffer, 0, bufSize);
                    downloadSize += bufSize;
                }

                if ((args[1]).contains("/")) {
                    String [] tempArr = args[1].split("/");
                    args[1] = tempArr[tempArr.length-1];
                }


                synchronized (fileSystemMutex) {
                    File file = new File(args[1]);
                    FileOutputStream fos = new FileOutputStream(file);
                    fileOut.writeTo(fos);
                    fos.close();
                }

                fileOut.close();

            } catch (IOException e ) {
                System.out.println("Error downloading file.");
            }

            System.out.println("Download success! " + downloadSize + " Bytes copied.");
            printPrompt();
        }


        /* ========================= */
        /* ===        put        === */
        /* ========================= */

        private void put(String line, String[] args) throws IOException {

            File file;
            synchronized (fileSystemMutex) {
                file = new File(args[1]);
            }

            // Verify file exists
            if (!file.exists()) {
                System.out.println("FTP Error: Local file \"" + args[1] + "\" does not exist.");
                printPrompt();
                return;
            }

            // Send command
            socket_out.writeUTF(line);

            int commandId;
            try {
                commandId = socket_in.readInt();
            } catch (IOException e) {
                System.out.println("Error reading command Id");
                return;
            }
            System.out.println("Command ID : " + commandId);
            if (isThread) printPrompt();

            byte[] byteArr;

            String filename = args[1];
            synchronized (fileSystemMutex) {
                Path filePath = Paths.get(filename);
                if (filename.charAt(0) != '/') {
                    filePath = Paths.get(filename).toAbsolutePath();
                }

                // Send file as byte array
                byteArr = Files.readAllBytes(filePath);
            }

            // socket_out.write(byteArr);

            int chunkSize = 1000;
            int remainder = byteArr.length % chunkSize;
            int numChunks = byteArr.length / chunkSize;

            // Write fileSize
            socket_out.writeInt(byteArr.length);

            if (remainder != 0) numChunks++;
            for (int i = 0; i < numChunks; i++) {

                int offset = i * chunkSize;
                int length = chunkSize;

                if (i == numChunks - 1 && remainder != 0) {
                    length = remainder;
                }

                // Writes data
                socket_out.write(byteArr, offset, length);

                // TODO: Check heartbeat
                boolean terminated = (socket_in.readInt() != 0);

                if (terminated) {
                    System.out.println("\nPut command " + commandId + " terminated");
                    printPrompt();
                    return;
                }
            }
            socket_out.flush();

            // Get response status
            int code = socket_in.readInt();
            if (code != 0) {
                System.out.println("FTP Error: Upload failed");
            } else {
                System.out.println("Upload success! " + byteArr.length + " Bytes copied.");
            }

            printPrompt();
        }


        /* ========================= */
        /* ===         cd        === */
        /* ========================= */

        private void cd(String line, String[] cmd_args) throws IOException {

            if (cmd_args.length != 2) {
                System.out.println("FTP Error: Must enter directory");
                printPrompt();
                return;
            }
            socket_out.writeUTF(line);
            printInput();
        }


        /* ========================= */
        /* ===       mkdir       === */
        /* ========================= */

        private void mkdir(String line) throws IOException {
            socket_out.writeUTF(line);
            int code = socket_in.readInt();
            if (code != 0) {
                System.out.println("FTP Error: Directory already exists");
                printPrompt();
                return;
            }
            printInput();
        }


        /* ========================= */
        /* ===     terminate     === */
        /* ========================= */

        private void terminate(String[] cmd_args) throws IOException {
            if (cmd_args.length < 2) {
                System.out.println("Error: Terminate command requires command id");
                return;
            }

            Socket terminate_socket;
            DataOutputStream out;

            long startTime = System.currentTimeMillis();
            long timeElapsed;
            int timeOutTime = 10000;

            while (true) {

                // Establish a connection
                try {
                    terminate_socket = new Socket(address, tport);
                    break;
                } catch (IOException e) {
                    // Errors expected, simply tries again 1 second later
                }

                // CHECK IF THREAD SHOULD GIVE UP
                timeElapsed = System.currentTimeMillis() - startTime;
                if (timeElapsed > timeOutTime) {
                    System.out.println("Error: Could not connect to terminate server " + address + ":" + tport);
                    return;
                }

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            try {
                // Gets input / output streams for the socket
                out = new DataOutputStream(terminate_socket.getOutputStream());
                out.writeUTF(cmd_args[1]);
            } catch (IOException e) {
                e.printStackTrace();
            }

            printPrompt();
        }


        /* ========================= */
        /* ===        quit       === */
        /* ========================= */

        private void quit() throws IOException {
            socket_out.writeUTF("quit");
            close();
        }


        /* ========================= */
        /* ===       other       === */
        /* ========================= */

        private void other(String line) throws IOException {
            if (line != null && line.length() > 0) {
                socket_out.writeUTF(line);
                printInput();
            }
        }

        private void getClientDir() {
            if (!command.split(" ")[0].equalsIgnoreCase("quit")) {
                try {
                    String command = "$gwd " + clientId;
                    socket_out.writeUTF(command);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private void cdClient(String[] cmd_args) throws IOException {

            if (cmd_args.length != 2) {
                System.out.println("FTP Error: Must enter directory");
                printPrompt();
                return;
            }
            socket_out.writeUTF("$cd " + cmd_args[1] + " " + clientId);
            printInput();
        }

        private void quitClient() throws IOException {
            socket_out.writeUTF("$quit " + clientId);
        }


        /* ============================================= */
        /* ===                                       === */
        /* ===           Helper Functions            === */
        /* ===                                       === */
        /* ============================================= */

        private void printInput() {
            try {
                waitForResponse();
//                if (isThread)

                String output = "";
                while (socket_in.available() > 0) {
                    String input = socket_in.readUTF();
                    output += input;
                }

                if (isThread && output.length() > 0) System.out.println();

                if (!isThread || (isThread && output.length() > 0)) {
                    System.out.print(output);
                    printPrompt();
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void waitForResponse() throws IOException{
            while (socket_in.available() <= 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    //
                }
            }
        }

        private void printPrompt() {
            System.out.print(prompt);
        }


        /* ============================================= */
        /* ===                                       === */
        /* ===           Socket Functions            === */
        /* ===                                       === */
        /* ============================================= */

        private void connect(boolean uniqueClient) {

            //INIT CLIENT
            while (true) {

                // Establish a connection
                try {

                    // Get socket connection
                    socket = new Socket(address, nport);

                    // Gets input / output streams for the socket
                    socket_in = new DataInputStream(socket.getInputStream());
                    socket_out = new DataOutputStream(socket.getOutputStream());

                    if (uniqueClient) {
                        clientId = socket_in.readInt();
                    } else {
                        socket_in.readInt();
                    }

                    break;

                } catch (IOException e) {
                    // Errors expected, simply tries again 1 second later
                }

                if (uniqueClient) System.out.println("Unable to connect... Trying again in 1 second");

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        private void close() throws IOException {
            socket_in.close();
            socket_out.close();
            socket.close();
        }

        private boolean isClosed() {
            return socket.isClosed();
        }
    }
}
