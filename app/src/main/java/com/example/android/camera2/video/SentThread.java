package com.example.android.camera2.video;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;

public class SentThread implements Runnable {     //实现Runnable接口

    //成员变量定义私有
    private Socket s;
    private BufferedWriter bw;

    //带参构造，传入输出流对象
    public SentThread(Socket s, BufferedWriter bw) {
        this.s = s;
        this.bw = bw;
    }

    //重写run()方法
    @Override
    synchronized public void run() {
        try {
            while (true) {
                String heartMsg = "heart";
                bw.write(heartMsg);
                bw.newLine();
                bw.flush();
                Thread.sleep(2000);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
