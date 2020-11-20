package com.example.android.camera2.video

import android.content.Context
import android.text.TextUtils
import android.util.AndroidException
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

object DemoSmsLoader {
    private const val TAG = "DemoSmsLoader"

    fun loadSms(path: String){
        var file = File(path)

        if (!file.exists()) {
            println("$path is not exsit")
        }
        val inputStream: InputStream = File(path).inputStream()
        inputStream.bufferedReader().useLines { lines ->
            lines.forEach {
//                TedSDKLog.d(TAG, "sms: $it")
                if (!it.isEmpty()) {
                    println(it)
                }
            }
        }
    }



}

fun main(args: Array<String>) {
    val fileName:String = "G:\\android_code\\camera-samples\\Camera2Video\\app\\src\\main\\res\\raw\\fileName.txt"
    DemoSmsLoader.loadSms(fileName)
}