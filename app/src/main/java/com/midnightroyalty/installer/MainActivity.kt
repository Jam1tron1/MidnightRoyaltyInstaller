package com.midnightroyalty.installer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    enum class Mode { MLO, SCRIPT }
    private var mode = Mode.MLO
    private var selected: Uri? = null
    private lateinit var status: TextView
    private lateinit var password: EditText
    private lateinit var install: Button
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var username: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(42,60,42,30); gravity=Gravity.CENTER_HORIZONTAL }
        fun title(t:String,s:Float)=TextView(this).apply{text=t;textSize=s;gravity=Gravity.CENTER}
        root.addView(title("MIDNIGHT ROYALTY",28f)); root.addView(title("FiveM One-Click Installer",18f))
        val mlo=Button(this).apply{text="🏠  INSTALL MLO"}; val script=Button(this).apply{text="⚙️  INSTALL SCRIPT"}
        root.addView(mlo); root.addView(script)
        host=EditText(this).apply { hint="SFTP host"; setText("fx-ash-14.apollopanel.com") }
        port=EditText(this).apply { hint="SFTP port"; setText("2022"); inputType=InputType.TYPE_CLASS_NUMBER }
        username=EditText(this).apply { hint="SFTP username"; setText("jam1.90d57ae1") }
        password=EditText(this).apply { hint="RocketNode SFTP password"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(host); root.addView(port); root.addView(username); root.addView(password)
        val test=Button(this).apply{text="🔌 TEST SERVER CONNECTION"}; root.addView(test)
        val select=Button(this).apply{text="📦 SELECT ZIP"}; root.addView(select)
        install=Button(this).apply{text="🚀 ONE-CLICK CHECK + INSTALL"; isEnabled=false}; root.addView(install)
        status=TextView(this).apply{text="Mode: MLO\nDestination: /home/container/resources/[MLOS]/"; setPadding(0,30,0,0)}; root.addView(status)
        setContentView(root)
        mlo.setOnClickListener { mode=Mode.MLO; status.text="Mode: MLO\nDestination: /home/container/resources/[MLOS]/" }
        script.setOnClickListener { mode=Mode.SCRIPT; status.text="Mode: SCRIPT\nDestination: /home/container/resources/[standalone]/" }
        test.setOnClickListener { testConnection() }
        select.setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/zip" },42) }
        install.setOnClickListener { selected?.let { analyze(it) } }
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==42&&resultCode==Activity.RESULT_OK){
            selected=data?.data; install.isEnabled=selected!=null; status.text="Selected package. Ready to check."
        }
    }

    private fun testConnection() {
        if (password.text.isNullOrBlank()) { status.text="❌ Enter your SFTP password first."; return }
        status.text="🔌 Testing server connection…"
        Thread {
            var session: com.jcraft.jsch.Session? = null
            var channel: com.jcraft.jsch.ChannelSftp? = null
            try {
                session = com.jcraft.jsch.JSch().getSession(username.text.toString().trim(), host.text.toString().trim(), port.text.toString().toInt())
                session.setPassword(password.text.toString())
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256")
                session.connect(15000)
                channel = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
                channel.connect(15000)
                val remote = channel.pwd()
                runOnUiThread { status.text="✅ SERVER CONNECTION PASSED\nConnected by SFTP.\nRemote folder: $remote" }
            } catch (e: Exception) {
                runOnUiThread { status.text="❌ CONNECTION FAILED\n${e.message ?: e.javaClass.simpleName}" }
            } finally {
                try { channel?.disconnect() } catch (_:Exception) {}
                try { session?.disconnect() } catch (_:Exception) {}
            }
        }.start()
    }

    private fun analyze(uri:Uri) {
        status.text="🔍 Checking package…"
        Thread {
            try {
                val names=mutableListOf<String>(); var manifest:String?=null; var unsafe=false
                contentResolver.openInputStream(uri)!!.use { input -> ZipInputStream(input).use { zip ->
                    while(true){
                        val e=zip.nextEntry?:break
                        val n=e.name.replace('\\','/')
                        if(n.startsWith("/")||n.split('/').contains("..")) unsafe=true
                        names+=n
                        if(n.endsWith("fxmanifest.lua")||n.endsWith("__resource.lua")) manifest=zip.readBytes().toString(Charsets.UTF_8)
                    }
                }}
                val hasManifest=manifest!=null
                val hasStream=names.any{it.contains("/stream/")||it.startsWith("stream/")}
                val hasMap=names.any{it.endsWith(".ymap")}
                val looksMlo=hasMap||manifest?.contains("this_is_a_map") == true
                val warnings=mutableListOf<String>()
                if(!hasManifest) warnings+="Missing resource manifest"
                if(mode==Mode.MLO&&!hasStream) warnings+="No stream folder"
                if(mode==Mode.MLO&&!looksMlo) warnings+="Package does not look like an MLO/map"
                if(mode==Mode.SCRIPT&&looksMlo) warnings+="This looks like an MLO, not a script"
                if(unsafe) warnings+="Unsafe ZIP path detected"
                val deps=Regex("dependenc(?:y|ies)[^\\n]*['\"]([^'\"]+)['\"]").findAll(manifest?:"").map{it.groupValues[1]}.toList()
                runOnUiThread {
                    status.text=buildString {
                        append(if(warnings.isEmpty()) "✅ PACKAGE CHECK PASSED" else "⚠️ CHECK NEEDS ATTENTION")
                        append("\nFiles: ${names.size}\nManifest: ${if(hasManifest) "OK" else "MISSING"}\n")
                        if(deps.isNotEmpty()) append("Dependencies: ${deps.joinToString()}\n")
                        warnings.forEach{append("• $it\n")}
                        append("\nLive SFTP upload is the next connection step.")
                    }
                }
            } catch(e:Exception){ runOnUiThread { status.text="❌ Could not inspect ZIP: ${e.message}" } }
        }.start()
    }
}
