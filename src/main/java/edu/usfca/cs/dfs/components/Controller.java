package edu.usfca.cs.dfs.components;

import edu.usfca.cs.dfs.messages.Messages;
import edu.usfca.cs.dfs.structures.ComponentAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class Controller {

    private static final Logger logger = LoggerFactory.getLogger(Controller.class);

    private final int port;

    private Set<ComponentAddress> onlineStorageNodes = new HashSet<>();

    public Controller(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: Controller port");
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);

        logger.info("Starting controller...");

        new Controller(port).start();
    }

    public void start() throws Exception {
        ServerSocket serverSocket = new ServerSocket(port);
        while (true) {
            Socket socket = serverSocket.accept();
            new Thread(new ProcessIncomingMessageRunnable(onlineStorageNodes, socket)).start();
        }
    }

    private static class ProcessIncomingMessageRunnable implements Runnable {
        private static final Logger logger = LoggerFactory.getLogger(ProcessIncomingMessageRunnable.class);
        private final Set<ComponentAddress> onlineStorageNodes;
        private final Socket socket;

        public ProcessIncomingMessageRunnable(Set<ComponentAddress> onlineStorageNodes, Socket socket) {
            this.onlineStorageNodes = onlineStorageNodes;
            this.socket = socket;
        }

        @Override
        public void run() {
            while (!socket.isClosed()) {
                try {
                    Messages.MessageWrapper msgWrapper = Messages.MessageWrapper.parseDelimitedFrom(socket.getInputStream());

                    if (msgWrapper.hasHeartbeatMsg()) {
                        Messages.Heartbeat msg = msgWrapper.getHeartbeatMsg();
                        ComponentAddress storageNodeAddress = new ComponentAddress(
                                msg.getStorageNodeHost(),
                                msg.getStorageNodePort());

                        Map<String, SortedSet<Integer>> fileChunks = toFileChunksMap(msg.getFileChunksList());

                        logger.trace("Received heartbeat from " + storageNodeAddress + " with file chunks: " + fileChunks);
                        onlineStorageNodes.add(storageNodeAddress);
                    } else if (msgWrapper.hasGetStoragesNodesRequestMsg()) {
                        List<Messages.GetStorageNodesResponse.StorageNode> msgStorageNodeList = new ArrayList<>(onlineStorageNodes.size());
                        for (ComponentAddress onlineStorageNode : onlineStorageNodes) {
                            msgStorageNodeList.add(Messages.GetStorageNodesResponse.StorageNode.newBuilder()
                                    .setHost(onlineStorageNode.getHost())
                                    .setPort(onlineStorageNode.getPort())
                                    .build()
                            );
                        }
                        Messages.GetStorageNodesResponse storageNodesResponse = Messages.GetStorageNodesResponse.newBuilder()
                                .addAllNodes(msgStorageNodeList)
                                .build();
                        Messages.MessageWrapper responseMsgWrapper = Messages.MessageWrapper.newBuilder()
                                .setGetStorageNodesResponseMsg(storageNodesResponse)
                                .build();
                        responseMsgWrapper.writeDelimitedTo(socket.getOutputStream());
                    }
                } catch (IOException e) {
                    logger.error("Error reading from heartbeat socket", e);
                }
            }
        }

        private Map<String, SortedSet<Integer>> toFileChunksMap(List<Messages.Heartbeat.FileChunks> pbFileChunks) {
            Map<String, SortedSet<Integer>> result = new HashMap<>();
            for (Messages.Heartbeat.FileChunks pbFileChunk : pbFileChunks) {
                String filename = pbFileChunk.getFilename();
                TreeSet<Integer> sequenceNos = new TreeSet<>(pbFileChunk.getSequenceNosList());
                result.put(filename, sequenceNos);
            }
            return result;
        }
    }

}
