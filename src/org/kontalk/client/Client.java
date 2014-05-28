/*
 *  Kontalk Java client
 *  Copyright (C) 2014 Kontalk Devteam <devteam@kontalk.org>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.client;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bouncycastle.openpgp.PGPException;
import org.jivesoftware.smack.Connection;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.RosterListener;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.NotFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.kontalk.KontalkConfiguration;
import org.kontalk.MyKontalk;
import org.kontalk.crypto.PersonalKey;
import org.kontalk.model.KontalkMessage;

/**
 *
 * @author Alexander Bikadorov <abiku@cs.tu-berlin.de>
 */
public class Client implements PacketListener, Runnable {
    private final static Logger LOGGER = Logger.getLogger(Client.class.getName());

    public final static String KONTALK_NETWORK = "kontalk.net";
    public final static LinkedBlockingQueue<Task> TASK_QUEUE = new LinkedBlockingQueue();

    public static enum Command {CONNECT, DISCONNECT};

    private final MyKontalk mModel;
    private final KontalkConfiguration mConfig;
    private final EndpointServer mServer;
    protected Connection mConn;

    // Limited connection flag.
    //protected boolean mLimited;

    public Client(MyKontalk model) {
        mModel = model;
        mConfig = KontalkConfiguration.getInstance();
        String network = mConfig.getString("server.network");
        String host = mConfig.getString("server.host");
        int port = mConfig.getInt("server.port");
        mServer = new EndpointServer(network, host, port);
        //mLimited = limited;

        // open smack debug window when connecting
        //Connection.DEBUG_ENABLED = true;
    }

    private void connect(PersonalKey key){

       this.disconnect();
       mModel.statusChanged(MyKontalk.Status.CONNECTING);

       synchronized (this) {

            // create connection
            try {
                mConn = new KontalkConnection(mServer,
                        key.getBridgePrivateKey(),
                        key.getBridgeCertificate());
            } catch (XMPPException | PGPException ex) {
                LOGGER.log(Level.WARNING, "can't create connection", ex);
                return;
            }

            // connect
            try {
                mConn.connect();
            } catch (XMPPException ex) {
                LOGGER.log(Level.WARNING, "can't connect", ex);
                return;
            }

            // listeners
            RosterListener rl = new MyRosterListener(mConn.getRoster(), this);
            mConn.getRoster().addRosterListener(rl);
            PacketFilter messageFilter = new PacketTypeFilter(Message.class);
            mConn.addPacketListener(new MessageListener(this), messageFilter);
            PacketFilter vCardFilter = new PacketTypeFilter(VCard4.class);
            mConn.addPacketListener(new VCardListener(), vCardFilter);
             // fallback
            mConn.addPacketListener(this, new AndFilter(
                    new NotFilter(messageFilter), new NotFilter(vCardFilter)));

            // login
            try {
                // the dummy values are not actually used
                // server does authentification based purely on the pgp key
                mConn.login("dummy", "dummy");
            } catch (XMPPException ex) {
                LOGGER.log(Level.WARNING, "can't login", ex);
                // TODO: most likely the pgp key is invalid, tell that to user
                return;
            }
        }

        LOGGER.info("Connected!");

        mModel.statusChanged(MyKontalk.Status.CONNECTED);
    }

    public void disconnect() {
        synchronized (this) {
            if (mConn != null) {
                mConn.disconnect();
                mConn = null;
            }
        }
        mModel.statusChanged(MyKontalk.Status.DISCONNECTED);
    }

    public void sendMessage(KontalkMessage message) {
        Message smackMessage = new Message();
        smackMessage.setPacketID(message.getXMPPID());
        smackMessage.setType(Message.Type.chat);
        smackMessage.setTo(message.getJID());
        smackMessage.setBody(message.getText());
        smackMessage.addExtension(new ServerReceiptRequest());
        sendPacket(smackMessage);
    }

    public void sendVCardRequest(String jid) {
        VCard4 vcard = new VCard4();
        vcard.setType(IQ.Type.GET);
        vcard.setTo(jid);
        sendPacket(vcard);
    }

    synchronized void sendPacket(Packet p) {
        if (mConn == null || !mConn.isAuthenticated()) {
            LOGGER.warning("can't send packet, not connected.");
            return;
        }
        mConn.sendPacket(p);
        LOGGER.info("sent packet: " + p.toXML());
    }

    @Override
    public void processPacket(Packet packet) {
        LOGGER.info("Got packet: "+packet.toXML());
    }

    @Override
    public void run() {
        while (true) {
            Task t;
            try {
                // blocking
                t = TASK_QUEUE.take();
            } catch (InterruptedException ex) {
                LOGGER.log(Level.WARNING, "interrupted while waiting ", ex);
                return;
            }
            switch (t.command) {
                case CONNECT:
                    this.connect((PersonalKey) t.args.get(0));
                    break;
                case DISCONNECT:
                    this.disconnect();
                    break;
            }
        }
    }

    public static class Task {

        final Command command;
        final List args;

        public Task(Command c, List a) {
            command = c;
            args = a;
        }
    }
}
