package edu.usfca.cs.dfs.components.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import edu.usfca.cs.dfs.DFSProperties;
import edu.usfca.cs.dfs.Utils;
import edu.usfca.cs.dfs.exceptions.ChecksumException;
import edu.usfca.cs.dfs.messages.Messages;
import edu.usfca.cs.dfs.structures.Chunk;
import edu.usfca.cs.dfs.structures.ComponentAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

public class Client {

    private static final Random random = new Random();

    private static final Logger logger = LoggerFactory.getLogger(Client.class);

    public static void main(String[] args) throws Exception {

        if (args.length < 3) {
            System.err.println("Usage: Client controller-host controller-port fileToSend");
            printHelp();
            System.exit(1);
        }

        ComponentAddress controllerAddr = new ComponentAddress(args[0], Integer.parseInt(args[1]));

        String command = args[2].toLowerCase();

        switch (command) {
            case "list-storage-nodes":
                listStorageNodes(controllerAddr);
                break;

            case "list-files":
                listFiles(controllerAddr, false);
                break;

            case "ls":
            case "list-filenames":
                listFiles(controllerAddr, true);
                break;

            case "upload-file":
                sendFile(args[3], controllerAddr);
                break;

            case "download-file":
                downloadFile(controllerAddr, args[3]);
                break;

            case "free-space":
                freeSpace(controllerAddr);
                break;

            default:
                printHelp();
                System.exit(1);
        }
    }

    private static void freeSpace(ComponentAddress controllerAddr) throws IOException {
        Socket socket = controllerAddr.getSocket();

        sendGetFreeSpaceRequest(socket);

        Messages.GetFreeSpaceResponse msg = receiveGetFreeSpaceResponse(socket);
        long freeSpace = msg.getFreeSpace();
        double gigabytes = freeSpace / 1e9;
        gigabytes = roundTo2Decimals(gigabytes);
        double gibibytes = freeSpace / 1024.0 / 1024.0 / 1024.0;
        gibibytes = roundTo2Decimals(gibibytes);
        System.out.println("Free space on DFS: " + gigabytes + " GB (" + gibibytes + " GiB)");
    }

    private static double roundTo2Decimals(double d) {
        return ((int) Math.round(100 * d)) / 100.0;
    }

    private static Messages.GetFreeSpaceResponse receiveGetFreeSpaceResponse(Socket socket) throws IOException {
        Messages.MessageWrapper responseMsgWrapper = Messages.MessageWrapper.parseDelimitedFrom(socket.getInputStream());
        if (!responseMsgWrapper.hasGetFreeSpaceResponseMsg()) {
            throw new IllegalStateException("Expected get free space response message, but got: " + responseMsgWrapper);
        }
        return responseMsgWrapper.getGetFreeSpaceResponseMsg();
    }

    private static void sendGetFreeSpaceRequest(Socket socket) throws IOException {
        Messages.MessageWrapper.newBuilder()
                .setGetFreeSpaceRequestMsg(Messages.GetFreeSpaceRequest.newBuilder().build())
                .build()
                .writeDelimitedTo(socket.getOutputStream());
    }

    private static void listFiles(ComponentAddress controllerAddr, boolean filenamesOnly) throws IOException {
        Socket socket = controllerAddr.getSocket();

        // Send request
        sendGetFilesRequest(socket);

        // Get response
        Messages.GetFilesResponse msg = receiveGetFilesResponse(socket);

        for (Messages.DownloadFileResponse downloadFileResponse : msg.getFilesList()) {
            String filename = downloadFileResponse.getFilename();
            if (filenamesOnly) {
                System.out.println(filename);
                continue;
            }
            System.out.println("Filename: " + filename);
            for (Messages.DownloadFileResponse.ChunkLocation chunkLocation : downloadFileResponse.getChunkLocationsList()) {
                System.out.print(String.format("    Chunk #%02d at ", chunkLocation.getSequenceNo()));
                SortedSet<ComponentAddress> storageNodes = new TreeSet<>();
                for (Messages.StorageNode msgStorageNode : chunkLocation.getStorageNodesList()) {
                    storageNodes.add(new ComponentAddress(msgStorageNode.getHost(), msgStorageNode.getPort()));
                }
                System.out.println(storageNodes);
            }
            System.out.println();
        }
    }

    private static Messages.GetFilesResponse receiveGetFilesResponse(Socket socket) throws IOException {
        Messages.MessageWrapper msgWrapper = Messages.MessageWrapper.parseDelimitedFrom(socket.getInputStream());
        if (!msgWrapper.hasGetFilesResponseMsg()) {
            throw new IllegalStateException("Expected GetFilesResponse message, got: " + msgWrapper);
        }
        return msgWrapper.getGetFilesResponseMsg();
    }

    private static void sendGetFilesRequest(Socket socket) throws IOException {
        Messages.MessageWrapper.newBuilder()
                .setGetFilesRequestMsg(Messages.GetFilesRequest.newBuilder().build())
                .build()
                .writeDelimitedTo(socket.getOutputStream());
    }

    private static void listStorageNodes(ComponentAddress controllerAddr) throws IOException {
        Set<ComponentAddress> storageNodes = new TreeSet<>(fetchStorageNodes(controllerAddr));
        if (storageNodes.isEmpty()) {
            System.out.println("No storage nodes found.");
            return;
        }

        String header = String.format("%-40s %5s", "Host", "Port");
        System.out.println(header);
        for (int i = 0; i < header.length(); ++i) {
            System.out.print("-");
        }
        System.out.println();

        for (ComponentAddress storageNode : storageNodes) {
            System.out.println(String.format("%-40s %5d", storageNode.getHost(), storageNode.getPort()));
        }
    }

    private static void downloadFile(ComponentAddress controllerAddr, String filename) throws IOException, ExecutionException, InterruptedException {
        Socket controllerSocket = controllerAddr.getSocket();
        logger.info("Asking controller " + controllerAddr + " about file " + filename);
        sendDownloadFileMsg(filename, controllerSocket);

        Messages.DownloadFileResponse downloadFileResponseMsg = receiveDownloadFileResponse(controllerSocket);

        SortedSet<Chunk> chunks = downloadChunks(filename, downloadFileResponseMsg);

        logger.info("Assembling chunks into file " + filename);
        File file = Chunk.createFileFromChunks(chunks, filename);
        long bytes = Files.size(file.toPath());
        double megabytes = bytes / 1e6;
        megabytes = roundTo2Decimals(megabytes); // round to two decimals
        logger.info("File assembled. Size: " + megabytes + " MB");

        // Cleanup
        logger.debug("Deleting all chunks from local filesystem");
        for (Chunk chunk : chunks) {
            chunk.getChunkLocalPath().toFile().delete();
        }
    }

    private static Messages.DownloadFileResponse receiveDownloadFileResponse(Socket controllerSocket) throws IOException {
        Messages.MessageWrapper msgWrapper = Messages.MessageWrapper.parseDelimitedFrom(controllerSocket.getInputStream());
        if (!msgWrapper.hasDownloadFileResponseMsg()) {
            throw new IllegalStateException("Controller is supposed to give back the DownloadFileResponse but got " + msgWrapper);
        }
        return msgWrapper.getDownloadFileResponseMsg();
    }

    private static void sendDownloadFileMsg(String filename, Socket controllerSocket) throws IOException {
        Messages.MessageWrapper msg = Messages.MessageWrapper.newBuilder()
                .setDownloadFileMsg(
                        Messages.DownloadFile.newBuilder()
                                .setFileName(filename)
                                .build()
                )
                .build();
        msg.writeDelimitedTo(controllerSocket.getOutputStream());
    }

    private static SortedSet<Chunk> downloadChunks(String filename, Messages.DownloadFileResponse downloadFileResponseMsg) throws ExecutionException, InterruptedException {

        int nThreads = DFSProperties.getInstance().getClientParallelDownloads();
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);

        List<DownloadChunkTask> tasks = new ArrayList<>();

        Map<Integer, List<ComponentAddress>> chunkLocations = parseChunkLocations(downloadFileResponseMsg);
        for (Map.Entry<Integer, List<ComponentAddress>> entry : chunkLocations.entrySet()) {
            int sequenceNo = entry.getKey();
            List<ComponentAddress> nodes = entry.getValue();

            DownloadChunkTask task = new DownloadChunkTask(filename, sequenceNo, nodes);
            tasks.add(task);
            executor.submit(task);
        }

        SortedSet<Chunk> chunks = new TreeSet<>();

        try {
            logger.debug("Waiting for all " + tasks.size() + " download tasks to finish...");
            List<Future<Chunk>> futures = executor.invokeAll(tasks);

            for (int sequenceNo : chunkLocations.keySet()) {
                chunks.add(futures.get(sequenceNo).get());
            }
        } finally {

            try {
                logger.trace("Attempting to shutdown executor");
                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } finally {
                if (!executor.isTerminated()) {
                    logger.error("Some tasks didn't finish.");
                }
                executor.shutdownNow();
                logger.trace("ExecutorService shutdown finished.");
            }
        }

        return chunks;
    }

    private static Chunk downloadChunk(String filename, int sequenceNo, Socket socket) throws IOException {
        sendDownloadChunkRequest(filename, sequenceNo, socket);

        Messages.MessageWrapper msgWrapper = Messages.MessageWrapper.parseDelimitedFrom(socket.getInputStream());
        if (!msgWrapper.hasStoreChunkMsg()) {
            throw new IllegalStateException("Response to DownloadChunk should have been StoreChunk. Got: " + TextFormat.printToString(msgWrapper));
        }

        return processStoreChunkMsg(msgWrapper);
    }

    private static void sendDownloadChunkRequest(String filename, int sequenceNo, Socket socket) throws IOException {
        Messages.MessageWrapper requestMsg = Messages.MessageWrapper.newBuilder()
                .setDownloadChunkMsg(
                        Messages.DownloadChunk.newBuilder()
                                .setFilename(filename)
                                .setSequenceNo(sequenceNo)
                                .build()
                )
                .build();
        requestMsg.writeDelimitedTo(socket.getOutputStream());
    }

    public static List<ComponentAddress> fetchStorageNodes(ComponentAddress controllerAddr) throws IOException {
        Socket socket = controllerAddr.getSocket();
        logger.debug("Asking for list of storage nodes...");
        sendGetStorageNodesRequest(socket);

        logger.debug("Waiting for list of storage nodes...");
        Messages.GetStorageNodesResponse responseMsg = receiveGetStorageNodesResponse(socket);

        return toComponentAddresses(responseMsg.getNodesList());
    }

    private static Messages.GetStorageNodesResponse receiveGetStorageNodesResponse(Socket socket) throws IOException {
        Messages.MessageWrapper receivedMsgWrapper = Messages.MessageWrapper.parseDelimitedFrom(socket.getInputStream());
        if (!receivedMsgWrapper.hasGetStorageNodesResponseMsg()) {
            throw new UnsupportedOperationException("Expected storage node list response, but got something else.");
        }
        return receivedMsgWrapper.getGetStorageNodesResponseMsg();
    }

    private static void sendGetStorageNodesRequest(Socket socket) throws IOException {
        Messages.GetStorageNodesRequest storageNodesRequestMsg = Messages.GetStorageNodesRequest.newBuilder().build();
        Messages.MessageWrapper sentMsgWrapper = Messages.MessageWrapper.newBuilder()
                .setGetStoragesNodesRequestMsg(storageNodesRequestMsg)
                .build();
        sentMsgWrapper.writeDelimitedTo(socket.getOutputStream());
    }

    private static Chunk processStoreChunkMsg(Messages.MessageWrapper msgWrapper) throws IOException {
        Messages.StoreChunk storeChunkMsg
                = msgWrapper.getStoreChunkMsg();
        logger.debug("Storing file name: "
                + storeChunkMsg.getFileName() + " Chunk #" + storeChunkMsg.getSequenceNo());

        String storageDirectory = DFSProperties.getInstance().getClientChunksDir();
        File storageDirectoryFile = new File(storageDirectory);
        if (!storageDirectoryFile.exists()) {
            if (!storageDirectoryFile.mkdir()) {
                System.err.println("Could not create storage directory.");
                System.exit(1);
            }
        }

        // Store chunk file
        String chunkFilename = storeChunkMsg.getFileName() + "-chunk" + storeChunkMsg.getSequenceNo();
        Path chunkFilePath = Paths.get(storageDirectory, chunkFilename);
        File chunkFile = chunkFilePath.toFile();
        if (chunkFile.exists()) {
            if (!chunkFile.delete()) {
                throw new RuntimeException("Unable to delete existing file before overwriting");
            }
        }
        logger.debug("Storing to file " + chunkFilePath);
        FileOutputStream fos = new FileOutputStream(chunkFile);
        storeChunkMsg.getData().writeTo(fos);
        fos.close();

        Utils.checkSum(chunkFile, storeChunkMsg.getChecksum());

        return new Chunk(storeChunkMsg.getFileName(), storeChunkMsg.getSequenceNo(), Files.size(chunkFilePath), storeChunkMsg.getChecksum(), chunkFilePath);
    }

    private static void sendFile(String filename, ComponentAddress controllerAddr) throws IOException {

        List<ComponentAddress> storageNodeAddresses = fetchStorageNodes(controllerAddr);

        int storageNodeIndex = random.nextInt(storageNodeAddresses.size());
        int nbStorageNodes = storageNodeAddresses.size();

        Chunk[] chunks = Chunk.createChunksFromFile(
                filename,
                DFSProperties.getInstance().getChunkSize(),
                DFSProperties.getInstance().getClientChunksDir());
        for (Chunk chunk : chunks) {
            int i = (storageNodeIndex + 1) % nbStorageNodes;
            storageNodeIndex = i;
            logger.trace("Will send chunk " + chunk.getSequenceNo() + " to node #" + i);

            ComponentAddress storageNodeAddr = storageNodeAddresses.get(i);

            logger.debug("Connecting to storage node " + storageNodeAddr);
            Socket socket = storageNodeAddr.getSocket();

            logger.debug("Sending chunk '" + chunk + "' to storage node " + storageNodeAddr);
            // Read chunk data from disk
            File chunkFile = chunk.getChunkLocalPath().toFile();
            FileInputStream fis = new FileInputStream(chunkFile);
            ByteString data = ByteString.readFrom(fis);
            fis.close();

            sendStoreChunkMsg(chunk, data, socket);

            logger.debug("Close connection to storage node " + storageNodeAddr.getHost());
            logger.debug("Deleting chunk file " + chunkFile.getName());
            if (!chunkFile.delete()) {
                logger.warn("Chunk file " + chunkFile.getName() + " could not be deleted.");
            }
            socket.close();
        }
    }

    private static void sendStoreChunkMsg(Chunk chunk, ByteString data, Socket socket) throws IOException {
        Messages.MessageWrapper.newBuilder()
                .setStoreChunkMsg(
                        Messages.StoreChunk.newBuilder()
                                .setFileName(chunk.getFilename())
                                .setSequenceNo(chunk.getSequenceNo())
                                .setChecksum(chunk.getChecksum())
                                .setData(data)
                                .build()
                )
                .build()
                .writeDelimitedTo(socket.getOutputStream());
    }

    private static Map<Integer, List<ComponentAddress>> parseChunkLocations(Messages.DownloadFileResponse downloadFileResponseMsg) {
        Map<Integer, List<ComponentAddress>> result = new HashMap<>();
        for (Messages.DownloadFileResponse.ChunkLocation chunkLocation : downloadFileResponseMsg.getChunkLocationsList()) {
            List<ComponentAddress> nodes = new ArrayList<>();
            for (Messages.StorageNode node : chunkLocation.getStorageNodesList()) {
                nodes.add(new ComponentAddress(node.getHost(), node.getPort()));
            }
            logger.debug("Chunk " + chunkLocation.getSequenceNo() + " is on " + nodes);
            result.put(chunkLocation.getSequenceNo(), nodes);
        }
        return result;
    }

    private static void printHelp() throws IOException {
        StringBuilder sb = new StringBuilder();
        InputStream is = Client.class.getClassLoader().getResourceAsStream("help.txt");
        char[] buf = new char[1024];
        int c;

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        while ((c = reader.read(buf)) != -1) {
            sb.append(new String(buf, 0, c));
        }
        reader.close();

        System.err.println(sb.toString());
    }

    static List<ComponentAddress> toComponentAddresses(List<Messages.StorageNode> list) {
        List<ComponentAddress> addresses = new ArrayList<>(list.size());
        for (Messages.StorageNode storageNode : list) {
            addresses.add(toComponentAddress(storageNode));
        }
        return addresses;
    }

    private static ComponentAddress toComponentAddress(Messages.StorageNode node) {
        return new ComponentAddress(node.getHost(), node.getPort());
    }

    private static class DownloadChunkTask implements Callable<Chunk> {

        private final String filename;
        private final int sequenceNo;
        private final List<ComponentAddress> storageNodes;

        public DownloadChunkTask(String filename, int sequenceNo, List<ComponentAddress> storageNodes) {
            this.filename = filename;
            this.sequenceNo = sequenceNo;
            this.storageNodes = storageNodes;
        }

        @Override
        public Chunk call() throws Exception {
            for (ComponentAddress storageNode : storageNodes) {
                try {
                    return downloadChunk(filename, sequenceNo, storageNode.getSocket());
                } catch (ConnectException | ChecksumException ce) {
                    // Just try the next node
                }
            }
            throw new ConnectException("Couldn't retrieve one good chunk (correct checksum) or connect to any of: " + storageNodes);
        }
    }

}
