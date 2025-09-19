package com.example.mathsya_v_01;

import io.socket.client.IO;
import io.socket.client.Socket;

public class SocketManager {


        private static SocketManager instance;
        private Socket socket;

        private SocketManager() {
            try {
                IO.Options options = new IO.Options();
                options.transports = new String[]{"websocket"};  // force WebSocket
                options.reconnection = true;
                options.reconnectionAttempts = Integer.MAX_VALUE;
                options.reconnectionDelay = 2000;  // retry every 2s
                options.timeout = 20000;  // 20s

                socket = IO.socket("http://10.42.0.1:5000", options);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        public static synchronized SocketManager getInstance() {
            if (instance == null) {
                instance = new SocketManager();
            }
            return instance;
        }


        public Socket getSocket() {
            return socket;
        }
    }

