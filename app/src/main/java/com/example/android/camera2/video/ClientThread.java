package com.example.android.camera2.video;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketException;

public class ClientThread extends Thread {

    private String ip;
    private int port;
    private Socket clientSock=null;
    private ReceiveCallback receiveCallback;
    private BufferedReader br;
    private BufferedWriter bw;

    public ClientThread(String ip, int port, ReceiveCallback receiveCallback) {
        this.ip = ip;
        this.port = port;
        this.receiveCallback = receiveCallback;
        String content;
        try {
            clientSock = new Socket(ip, port);
            br = new BufferedReader(new InputStreamReader(
                    clientSock.getInputStream()));
            bw = new BufferedWriter(new OutputStreamWriter(
                    clientSock.getOutputStream()));
            content = "connect";
        } catch (IOException e) {
            e.printStackTrace();
            content = "disconnect";
        }
        receiveCallback.callbackk(content);

//        try {
//            if (!clientSock.getKeepAlive()){
//                clientSock.setKeepAlive(true);
//            }
//            new Thread(new SentThread(clientSock, bw)).start();
//        } catch (SocketException e) {
//            e.printStackTrace();
//        }
    }

    synchronized public void sentCommand(String cmd) throws IOException {
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                clientSock.getOutputStream()));
        bw.write(cmd);
        bw.newLine();
        bw.flush();
    }

    @Override
    public void run() {
        if (clientSock!=null){
            try {
                String line = null;    //初始化_line
                while ((line = br.readLine()) != null) {
                    receiveCallback.callbackk(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
