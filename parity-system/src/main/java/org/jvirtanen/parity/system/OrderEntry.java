package org.jvirtanen.parity.system;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

class OrderEntry implements Runnable {

    private static final int TIMEOUT_MILLIS = 1000;

    private OrderEntryServer server;

    private List<Session> sessions;

    private List<Session> toClose;

    private Selector selector;

    private OrderEntry(OrderEntryServer server) throws IOException {
        this.server = server;

        this.sessions = new ArrayList<>();
        this.toClose  = new ArrayList<>();

        this.selector = Selector.open();

        this.server.getChannel().register(this.selector, SelectionKey.OP_ACCEPT, null);
    }

    public static OrderEntry create(int port) throws IOException {
        return new OrderEntry(OrderEntryServer.create(port));
    }

    @Override
    public void run() {
        int numKeys;

        while (true) {
            try {
                numKeys = selector.select(TIMEOUT_MILLIS);
            } catch (IOException e) {
                break;
            }

            if (numKeys > 0) {
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

                while (keys.hasNext()) {
                    SelectionKey key = keys.next();

                    if (key.isAcceptable())
                        accept();

                    if (key.isReadable())
                        receive((Session)key.attachment());

                    keys.remove();
                }
            }

            keepAlive();

            cleanUp();
        }
    }

    private void accept() {
        try {
            Session session = server.accept();
            if (session != null) {
                sessions.add(session);

                session.getTransport().getChannel().register(selector, SelectionKey.OP_READ, session);
            }
        } catch (IOException e) {
        }
    }

    private void receive(Session session) {
        try {
            if (session.getTransport().receive() < 0)
                toClose.add(session);
        } catch (IOException e) {
            toClose.add(session);
        }
    }

    private void keepAlive() {
        for (int i = 0; i < sessions.size(); i++) {
            Session session = sessions.get(i);

            try {
                session.getTransport().keepAlive();

                if (session.hasHeartbeatTimeout())
                    toClose.add(session);
            } catch (IOException e) {
                toClose.add(session);
            }
        }
    }

    private void cleanUp() {
        for (int i = 0; i < toClose.size(); i++) {
            Session session = toClose.get(i);

            sessions.remove(session);

            try {
                session.getTransport().close();
            } catch (IOException e) {
            }
        }

        if (!toClose.isEmpty())
            toClose.clear();
    }

}
