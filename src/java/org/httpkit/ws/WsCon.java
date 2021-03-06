package org.httpkit.ws;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.httpkit.server.HttpServer;

import clojure.lang.IFn;

public class WsCon {
    final SelectionKey key;
    final List<IFn> onRecieveListeners = new ArrayList<IFn>(1);
    final List<IFn> onCloseListeners = new ArrayList<IFn>(1);
    private volatile boolean isClosedRuned = false;

    final private HttpServer server;

    // message sent before handle shake should be queued
    public volatile boolean handleShakeSent = false;
    private List<ByteBuffer> queueMessages = new LinkedList<ByteBuffer>();

    public WsCon(SelectionKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    public void addOnCloseListener(IFn fn) {
        synchronized (onCloseListeners) {
            onCloseListeners.add(fn);
        }
    }

    public void addRecieveListener(IFn fn) {
        synchronized (onRecieveListeners) {
            onRecieveListeners.add(fn);
        }
    }

    public void clientClosed(int status) {
        onClose(status);
    }

    private void onClose(int status) {
        if (!isClosedRuned) {
            isClosedRuned = true;
            IFn[] listeners;
            synchronized (onCloseListeners) {
                listeners = new IFn[onCloseListeners.size()];
                listeners = onCloseListeners.toArray(listeners);
            }
            for (IFn l : listeners) {
                l.invoke(status);
            }
        }
    }

    public void messageRecieved(final String mesg) {
        IFn[] listeners;
        synchronized (onRecieveListeners) {
            listeners = new IFn[onRecieveListeners.size()];
            listeners = onRecieveListeners.toArray(listeners);
        }
        for (IFn l : listeners) {
            l.invoke(mesg);
        }
    }

    public void send(final String mesg) {
        ByteBuffer buffer = WSEncoder.encode(mesg);
        if (handleShakeSent) {
            WsServerAtta atta = (WsServerAtta) key.attachment();
            atta.addBuffer(buffer);
            server.queueWrite(key);
        } else {
            synchronized (queueMessages) {
                queueMessages.add(buffer);
            }
        }
    }

    public void flushQueuedMesgs() { // only called once
        synchronized (queueMessages) {
            if (queueMessages.size() > 0) {
                WsServerAtta atta = (WsServerAtta) key.attachment();
                for (ByteBuffer bb : queueMessages) {
                    atta.addBuffer(bb);
                }
                server.queueWrite(key);
            }
            queueMessages = null; // for GC
        }
    }

    public void serverClose() {
        serverClose(CloseFrame.CLOSE_NORMAL);
    }

    public void serverClose(int status) {
        ByteBuffer s = ByteBuffer.allocate(2).putShort((short) status);
        WsServerAtta atta = (WsServerAtta) key.attachment();
        atta.addBuffer(WSEncoder.encode(WSDecoder.OPCODE_CLOSE, s.array()));
        atta.closeOnfinish = true;
        server.queueWrite(key);
        onClose(status);
    }

    public String toString() {
        Socket s = ((SocketChannel) key.channel()).socket();
        return "WsCon[" + s.getLocalSocketAddress() + "<->" + s.getRemoteSocketAddress() + "]";
    }
}
