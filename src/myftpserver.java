import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class myftpserver {

    // Port information
    private int nport;
    private int tport;

    // FTP Socket Protocol flags
    private static final int EOF = 0;
    private static final int OK = 0;
    private static final int ERR = 1;

    // Multi-Threaded Locks
    private final Object commandIdMutex = new Object();
    private final Object commandMapMutex = new Object();
    private final Object clientMapMutex = new Object();
    private final Object clientRunningMutex = new Object();
    private final Object clientDirectoryMutex = new Object();
    private final Object clientTerminateMutex = new Object();
    private final Object fileSystemMutex = new Object();

    // Threads
    private Terminator terminator;
    private Listener listener;
    private Map<Integer, Client> clients;
    private int commandId;

    // Constructor with port
    public myftpserver(int nport, int tport) {
        this.nport = nport;
        this.tport = tport;
        init();
    }

    private void init() {

        commandId = 0;
        clients = new HashMap<>();

        terminator = new Terminator(tport);
        terminator.start();

        listener = new Listener(nport);
        listener.start();
    }

    private int iterateCommandID() {
        synchronized (commandIdMutex) {
            commandId++;
            return commandId;
        }
    }

    public static void main (String args[]) {
        int nport = 5000;
        int tport = 5001;
        if (args.length != 2) {
            System.out.println("FTP Server Error: Expecting 2 arguments for the normal port number and"
                    + "the termination port number");
            System.exit(0);
        } else {
            try {
                nport = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("FTPServer Error: Unable to recognize normal port number.");
                System.exit(0);
            }
            try {
                tport = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.out.println("FTPServer Error: Unable to recognize termination port number.");
                System.exit(0);
            }
        }
        new myftpserver(nport, tport);
    }

    /* ============================================= */
    /* ===                                       === */
    /* ===           LISTENER THREAD             === */
    /* ===                                       === */
    /* ============================================= */


    private class Listener extends Thread implements Runnable {

        private int port;
        private Socket socket;
        private ServerSocket server;

        Listener(int port) {
            super();
            this.port = port;
        }

        public void run() {
            listen();
        }

        private void listen() {

            int handlerId = 0;

            // CREATE SERVER
            try {
                server = new ServerSocket(port);
            } catch (IOException e) {
                System.out.println("FTP Server Error: Error creating socket.");
                System.exit(0);
            } catch (SecurityException e) {
                System.out.println("FTP Server Error: (Security Exception) checkListen() failed.");
                System.exit(0);
            } catch (IllegalArgumentException e) {
                System.out.println("FTP Server Error: Normal_Port " + port + " is outside valid range.");
                System.exit(0);
            }

            System.out.println("Waiting for clients ...");

            while (true) {

                // ACCEPT CONNECTION
                try {
                    socket = server.accept();
                    new DataOutputStream(socket.getOutputStream()).writeInt(handlerId);
                } catch (IOException e) {
                    System.out.println("FTP Server Error: Error connecting to client... Restarting.");
                    continue;
                } catch (SecurityException e) {
                    System.out.println("FTP Server Error: (Security Exception) Cannot accept incoming connection.");
                    System.exit(0);
                }

                // Create client handler
                Client handler = new Client(socket, handlerId);
                handler.start();

                synchronized (clientMapMutex) {
                    clients.put(handlerId, handler);
                }
                handlerId ++;
            }
        }
    }

    /* ============================================= */
    /* ===                                       === */
    /* ===          TERMINATOR THREAD            === */
    /* ===                                       === */
    /* ============================================= */

    private class Terminator extends Thread {

        private Socket socket;
        private ServerSocket server;
        private DataInputStream socket_in;
        boolean running;

        private Map<Integer, Client> map;
        private int port;

        Terminator(int port) {
            super();
            this.port = port;
        }

        public void run() {
            init();
            listen();
        }

        private void init() {

            map = new HashMap<>();
            running = true;

            // CREATE SERVER
            try {
                server = new ServerSocket(port);
            } catch (IOException e) {
                System.out.println("FTP Server Error: Error creating terminator socket.");
                System.exit(0);
            } catch (SecurityException e) {
                System.out.println("FTP Server Error: (Security Exception) checkListen() failed in terminator socket.");
                System.exit(0);
            } catch (IllegalArgumentException e) {
                System.out.println("FTP Server Error: Terminator Port " + port + " is outside valid range.");
                System.exit(0);
            }
        }

        private void listen() {

            while (running) {

                // ACCEPT CONNECTION
                try {
                    socket = server.accept();
                } catch (IOException e) {
                    System.out.println("Error connecting to client... Restarting.");
                    continue;
                } catch (SecurityException e) {
                    System.out.println("Security Exception: Cannot accept incoming connection.");
                    System.exit(0);
                }

                // GET SOCKET INPUT/OUTPUT STREAM
                try {
                    socket_in = new DataInputStream(socket.getInputStream());
                } catch (IOException e) {
                    System.out.println("Error creating socket input and output streams... Restarting.");
                    continue;
                }

                terminateCommand();
            }
        }

        private void terminateCommand() {
            String input;
            int commandId = -1;

            try {
                input = socket_in.readUTF();
                commandId = Integer.parseInt(input);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (NumberFormatException e) {
                System.out.println("Invalid command id");
                return;
            }

            if (commandId < 0) {
                System.out.println("Invalid command id");
                return;
            }

            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            Client client = map.get(commandId);
            if (client != null) {
                client.terminate();
            } else {
                System.out.println("No active command associated with id " + commandId);
            }
        }

        private void addCommand(int commandId, Client handler) {
            synchronized (commandMapMutex) {
                if (handler != null) {
                    map.put(commandId, handler);
                }
            }
        }

        private void removeCommand(int commandId) {
            synchronized (commandMapMutex) {
                if (map.get(commandId) != null) {
                    map.remove(commandId);
                }
            }
        }

        private void printStatus() {
            synchronized (commandMapMutex) {
                System.out.println("\t> # Active Commands\t" + map.size());
            }
        }
    }


    /* ============================================= */
    /* ===                                       === */
    /* ===            CLIENT THREAD              === */
    /* ===                                       === */
    /* ============================================= */

    private class Client extends Thread implements Runnable {

        private Socket socket;
        private DataInputStream socket_in;
        private DataOutputStream socket_out;
        private Path curDir;
        private boolean shouldTerminate;
        private boolean running;
        private boolean unique;
        private int id;

        Client(Socket socket, int id) {
            super();
            this.socket = socket;
            this.id = id;
            this.shouldTerminate = false;
            setRunning(true);
            try {
                socket_in = new DataInputStream(socket.getInputStream());
                socket_out = new DataOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                System.out.println("Error creating socket input and output streams...");
                setRunning(false);
            }
        }

        /* ========================= */
        /* ===        run        === */
        /* ========================= */

        public void run() {
            // Clients must begin in the server's current directory
            setRelativeDir("");

            // Keep track of current command id
            int commandId;

            // User input
            String command;

            try {

                // While the client has not quit...
                while (running) {

                    command = socket_in.readUTF();
                    commandId = iterateCommandID();
                    String[] args = command.split(" ");

                    // Add to active commands map
                    terminator.addCommand(commandId, this);

                    switch (args[0].toLowerCase()) {

                        // Normal Commands
                        case "get":
                            get(args[1]);
                            break;
                        case "put":
                            put(args[1]);
                            break;
                        case "delete":
                            delete(args[1]);
                            break;
                        case "ls":
                            ls(args);
                            break;
                        case "cd":
                            cd(args[1]);
                            break;
                        case "mkdir":
                            mkdir(args[1]);
                            break;
                        case "pwd":
                            pwd();
                            break;
                        case "quit":
                            quit();
                            break;
                        case "":
                            break;

                        // Commands for cross-client comm.
                        case "$gwd":
                            gwd(args[1]);
                            break;
                        case "$cd":
                            cdClient(args[1], args[2]);
                            break;
                        case "$quit":
                            quitClient(args[1]);
                            break;
                        case "$":
                            printStatus();
                            socket_out.writeUTF("");
                            break;

                        // Default
                        default:
                            socket_out.writeUTF("FTP Error: Invalid command \"" + command + "\"\n");
                            break;
                    }

                    // Remove from active commands map
                    terminator.removeCommand(commandId);
                }
            } catch (EOFException e) {
                //
            } catch (IOException e) {
                System.out.println("\n*** Connection broken with client ***");
            }
            cleanExit();
        }


        /* ========================= */
        /* ===        get        === */
        /* ========================= */

        /* Socket Protocol:
         *          1. Send Command ID
         *          2. Send file status (Exists or not)
         *          3. Send file size
         *   Loop:  4. Send packet size / EOF
         *          5. Send file packet data
         *          6. Send download status (after loop)
         */
        private void get(String filename) throws IOException {

            // 1. Send Command ID
            socket_out.writeInt(commandId);

            // 2. Check and send file status (p1)
            if (filename == null) {
                socket_out.writeInt(ERR);
                return;
            }

            // Check for absolute or relative path
            Path filePath;
            if (filename.charAt(0) != '/')
                filename = appendFileNameToCurDir(filename);
            filePath = getPath(filename);

            // 2. Check and send file status (p2)
            if (!fileExists(filePath)) {
                socket_out.writeInt(ERR);
                return;
            } else {
                socket_out.writeInt(OK);
            }
            socket_out.flush();

            // Load file
            byte[] byteArr;
            synchronized (fileSystemMutex) {
                byteArr = Files.readAllBytes(filePath);
            }

            // Set file data
            int packetSize = 1000;
            int remainder = byteArr.length % packetSize;
            int numPackets = byteArr.length / packetSize;

            // Add extra iteration to send remaining data
            if (remainder != 0) numPackets++;

            // 3. Send file size
            socket_out.writeInt(byteArr.length);

            // For every packet...
            for (int i = 0; i < numPackets; i++) {

                // Get the current packet's offset in the byte array
                int offset = i * packetSize;

                // Get the current packet's length in the byte array
                int length = packetSize;

                // If we're dealing with a partial packet, set it to the remainder
                if (i == numPackets - 1 && remainder != 0) length = remainder;

                // Check termination signal
                synchronized (clientTerminateMutex) {

                    // System.out.println("(" + i + ") ShouldTerminate: " + shouldTerminate);

                    // 4. Send packet EOF
                    if (shouldTerminate) {
                        socket_out.writeInt(EOF);
                        shouldTerminate = false;
                        return;

                    // 4. Send packet size
                    } else {
                        socket_out.writeInt(length);
                    }
                }

                // 5. Send file packet data
                socket_out.write(byteArr, offset, length);

                // Debug: Sleep
                // try {
                //     Thread.sleep(34);
                // } catch (InterruptedException e) {
                //     System.out.print("");
                // }
            }
            socket_out.flush();
        }


        /* ========================= */
        /* ===        put        === */
        /* ========================= */

        /* Socket Protocol:
         *          1. Send Command ID
         *          2. Receive file size
         *   Loop:  3. Receive file packet data
         *          4. Send termination status
         */
        private void put(String fileName) throws IOException {

            // 1. Send Command ID
            socket_out.writeInt(commandId);

            int downloadStatus = 0;
            try {

                // Initialize file data structures
                ByteArrayOutputStream fileOut = new ByteArrayOutputStream();
                byte[] buffer = new byte[1000];
                int bufSize;

                // 2. Receive file size
                int fileSize = socket_in.readInt();
                int downloadSize = 0;

                // System.out.println("fileSize: " + fileSize);

                // While download is incomplete
                while (downloadSize < fileSize) {

                    // 4. Receive file packet data
                    bufSize = socket_in.read(buffer, 0, buffer.length);

                    // Write packet to byte array
                    fileOut.write(buffer, 0, bufSize);
                    downloadSize += bufSize;

                    // Check termination signal
                    synchronized (clientTerminateMutex) {

                        // System.out.println("(" + downloadSize + " / " + fileSize + ") ShouldTerminate: " + shouldTerminate);

                        // 4. Send termination status (1)
                        if (shouldTerminate) {
                            socket_out.writeInt(ERR);
                            shouldTerminate = false;
                            return;

                        // 4. Send termination status (0)
                        } else {
                            socket_out.writeInt(OK);
                        }
                    }

                    // Debug: Sleep
                    // try {
                    //     Thread.sleep(34);
                    // } catch (InterruptedException e) {
                    //     System.out.print("");
                    // }
                }

                // Get file name from input
                String[] filePath = fileName.split("/");
                fileName = filePath[filePath.length-1];
                fileName = appendFileNameToCurDir(fileName);

                // Write file to file system
                synchronized (fileSystemMutex) {
                    File file = new File(fileName);
                    FileOutputStream fos = new FileOutputStream(file);
                    fileOut.writeTo(fos);
                    fos.close();
                }
                fileOut.close();

            } catch (IOException e) {
                downloadStatus = 1;
            }

            // 6. Send download status
            socket_out.writeInt(downloadStatus);
        }


        /* ========================= */
        /* ===       delete      === */
        /* ========================= */

        private void delete(String filename) throws IOException {
            // Get path of file to delete
            String absolutePath = getAbsolutePath(filename);
            Path delete_path = getPath(absolutePath);

            // Delete the file, if possible
            synchronized (fileSystemMutex) {
                File file = new File(delete_path.toString());
                if (file.exists()) {
                    file.delete();
                    socket_out.writeUTF("");
                } else {
                    socket_out.writeUTF("FTP Error: No such file or directory\n");
                }
            }
        }


        /* ========================= */
        /* ===         ls        === */
        /* ========================= */

        private void ls(String[] cmd_args) throws IOException {
            if (cmd_args.length > 1)
                socket_out.writeUTF("FTP Error: \"ls\" command doesn't take any arguments\n");
            exec("ls " + getCurDirString());
        }


        /* ========================= */
        /* ===         cd        === */
        /* ========================= */

        private void cd(String relativePath) throws IOException {
            // Get directory path to change to
            String absolutePath = getAbsolutePath(relativePath);
            Path cd_path = getPath(absolutePath);
            if (fileExists(cd_path)) {
                setCurDir(cd_path);
                socket_out.writeUTF("");
            } else {
                socket_out.writeUTF("FTP Error: No such file or directory\n");
            }
        }


        /* ========================= */
        /* ===       mkdir       === */
        /* ========================= */

        private void mkdir(String dirname) throws IOException {
            // Get path of directory to make
            String absolutePath = getAbsolutePath(dirname);
            Path cd_path = getPath(absolutePath);
            if (Files.exists(cd_path)) {
                socket_out.writeInt(ERR);
                return;
            } else {
                socket_out.writeInt(OK);
            }
            exec("mkdir " + absolutePath);
            socket_out.writeUTF("");
        }


        /* ========================= */
        /* ===        pwd        === */
        /* ========================= */

        private void pwd() throws IOException {
            socket_out.writeUTF(getCurDirString() + "\n");
        }


        /* ========================= */
        /* ===       quit        === */
        /* ========================= */

        private void quit() {
            setRunning(false);
        }


        /* ========================= */
        /* ===       $gwd        === */
        /* ========================= */

        private void gwd(String id) {
            int clientId = Integer.parseInt(id);
            setCurDir(clients.get(clientId).getCurDir().toAbsolutePath());
        }


        /* ========================= */
        /* ===        $cd        === */
        /* ========================= */

        private void cdClient(String relativePath, String id) throws IOException{

            int handlerId;
            try {
                handlerId = Integer.parseInt(id);
            } catch (NumberFormatException e) {
                return;
            }

            String absolutePath = getAbsolutePath(relativePath);
            Path cd_path = getPath(absolutePath);
            if (fileExists(cd_path)) {
                Path newPath = cd_path.toAbsolutePath().normalize();
                clients.get(handlerId).setCurDir(newPath);
            } else {
                socket_out.writeUTF("FTP Error: No such file or directory\n");
            }
            socket_out.writeUTF("");
        }


        /* ========================= */
        /* ===       $quit       === */
        /* ========================= */

        private void quitClient(String id) {
            int handlerId = Integer.parseInt(id);
            synchronized (clientMapMutex) {
                clients.get(handlerId).setRunning(false);
            }
        }

        /* ============================================= */
        /* ===                                       === */
        /* ===           Helper Functions            === */
        /* ===                                       === */
        /* ============================================= */

        private void exec(String cmd) throws IOException {
            Process process;
            process = Runtime.getRuntime().exec(cmd);
            socket_out.writeUTF(getProcessOutput(process));
            socket_out.flush();
        }

        private String getProcessOutput(Process p) throws IOException{
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, output = "";
            while ((line = br.readLine()) != null)
                output += line + "\n";
            return output;
        }

//        private void waitForResponse() throws IOException, InterruptedException {
//            while (socket_in.available() <= 0) {
//                Thread.sleep(50);
//            }
//        }

        private void setRunning(boolean running) {
            synchronized (clientRunningMutex) {
                this.running = running;
            }
        }

        private void setRelativeDir(String dir) {
            Path path;
            synchronized (fileSystemMutex) {
                path = Paths.get(dir).toAbsolutePath();
            }
            synchronized (clientDirectoryMutex) {
                curDir = path;
            }
        }

        private void setCurDir(Path dir) {
            synchronized (clientDirectoryMutex) {
                curDir = dir.toAbsolutePath().normalize();
            }
        }

        private Path getCurDir() {
            return curDir;
        }

        private Path getPath(String path) {
            synchronized (fileSystemMutex) {
                return Paths.get(path).toAbsolutePath();
            }
        }

        private String getCurDirString() {
            String curDirString;
            synchronized (clientDirectoryMutex) {
                curDirString = curDir.toString();
            }
            return curDirString;
        }

        private String appendFileNameToCurDir(String filepath) {
            String newFilepath;
            synchronized (clientDirectoryMutex) {
                newFilepath = curDir.toAbsolutePath().toString() + "/" + filepath;
            }
            return newFilepath;
        }

        private String getAbsolutePath(String relativePath) {
            String absolutePath = relativePath;
            if (relativePath.charAt(0) != '/')
                absolutePath = appendFileNameToCurDir(relativePath);
            return absolutePath;
        }

        private boolean fileExists(Path filePath) {
            boolean exists;
            synchronized (fileSystemMutex) {
                exists = Files.exists(filePath);
            }
            return exists;
        }

        private void terminate() {
            synchronized (clientTerminateMutex) {
                shouldTerminate = true;
            }
        }

        private void cleanExit () {
            clients.remove(id);
            try {
                socket.close();
                socket_in.close();
                socket_out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void printPrompt() {
            System.out.print("ftp server > ");
        }

        private void printStatus() {
            System.out.println();
            System.out.println("\t> Current Client Id\t" + id);
            System.out.println("\t> Current Command Id\t" + commandId);
            synchronized (clientMapMutex) {
                System.out.println("\t> # Active clients\t" + clients.size());
            }
            terminator.printStatus();
            System.out.println();
        }
    }
}

