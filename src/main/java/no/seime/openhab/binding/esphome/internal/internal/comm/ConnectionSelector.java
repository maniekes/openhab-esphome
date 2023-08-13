package no.seime.openhab.binding.esphome.internal.internal.comm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectionSelector {

    private final Logger logger = LoggerFactory.getLogger(ConnectionSelector.class);

    private Selector selector;

    private boolean keepRunning = true;

    private boolean selectorOpen;

    public ConnectionSelector() throws IOException {
        selector = Selector.open();
        selectorOpen = true;
    }

    private Map<SocketChannel, StreamHandler> connectionMap = new ConcurrentHashMap<>();

    public void start() {

        Thread selectorThread = new Thread(() -> {
            logger.debug("Starting selector thread");
            while (keepRunning) {
                try {
                    selector.select();
                    // token representing the registration of a SelectableChannel with a Selector
                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = keys.iterator();
                    while (keyIterator.hasNext()) {
                        SelectionKey readyKey = keyIterator.next();
                        // Tests whether this key's channel is ready to accept a new socket connection
                        if (readyKey.isReadable()) {
                            SocketChannel channel = (SocketChannel) readyKey.channel();
                            ByteBuffer buffer = ByteBuffer.allocate(128);
                            int read = channel.read(buffer);
                            StreamHandler streamHandler = (StreamHandler) readyKey.attachment();
                            if (read == -1) {
                                streamHandler.endOfStream();
                            } else {
                                try {
                                    streamHandler.processReceivedData(buffer);
                                } catch (ProtocolException e) {
                                    channel.close();
                                    streamHandler.onParseError(e);
                                }
                            }

                        }
                        keyIterator.remove();
                    }
                } catch (ClosedSelectorException e) {
                    logger.debug("Selector closed");
                    keepRunning = false;
                } catch (IOException e) {
                    logger.warn("Error while selecting", e);
                    keepRunning = false;
                }
            }
            logger.debug("Selector thread stopped");
        });
        selectorThread.setName("ESPHome connection handler");
        selectorThread.start();
    }

    public void stop() {
        if (selectorOpen) {
            keepRunning = false;
            selector.wakeup();
            try {
                selector.close();
            } catch (IOException e) {
                logger.debug("Error closing selector", e);
            }
            selectorOpen = false;
        }
    }

    public void register(SocketChannel socketChannel, StreamHandler packetStreamReader) {
        connectionMap.put(socketChannel, packetStreamReader);
        try {
            SelectionKey key = socketChannel.register(selector, SelectionKey.OP_READ);
            key.attach(packetStreamReader);
            selector.wakeup();
        } catch (IOException e) {
            logger.warn("Error while registering channel", e);
        }
    }

    public void unregister(SocketChannel socketChannel) {
        connectionMap.remove(socketChannel);

        try {
            socketChannel.shutdownInput();
            socketChannel.shutdownOutput();
            socketChannel.close();
        } catch (IOException e) {
            logger.warn("Error while closing channel", e);
        }
    }
}
