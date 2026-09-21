package com.example.myplayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class QueueActivity : AppCompatActivity() {

    private lateinit var adapter: QueueAdapter

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            reload()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val emptyBox = findViewById<View>(R.id.emptyBox)
        val tvCount = findViewById<TextView>(R.id.tvCount)
        val tvClear = findViewById<TextView>(R.id.tvClear)

        adapter = QueueAdapter(
            onClick = { idx, video ->
                // 이 영상부터 재생
                QueueManager.moveToTop(this, video.videoId)
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("VIDEO_ID", video.videoId)
                    putExtra("VIDEO_TITLE", video.title)
                    putExtra("VIDEO_CHANNEL", video.channel)
                    putExtra("VIDEO_THUMB", video.thumbnail)
                }
                startActivity(intent)
            },
            onRemove = { video ->
                QueueManager.remove(this, video.videoId)
                reload()
            },
            onMoveTop = { video ->
                QueueManager.moveToTop(this, video.videoId)
                reload()
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val touch = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    rv: RecyclerView,
                    vh: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = vh.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    QueueManager.move(this@QueueActivity, from, to)
                    adapter.submit(QueueManager.get(this@QueueActivity))
                    return true
                }
                override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            }
        )
        touch.attachToRecyclerView(recycler)

        tvClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("대기열")
                .setMessage("모두 비울까요?")
                .setPositiveButton("비우기") { _, _ ->
                    QueueManager.clear(this)
                    reload()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onStart() {
        super.onStart()
        handler.post(pollRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(pollRunnable)
    }

    private fun reload() {
        val list = QueueManager.get(this)
        adapter.submit(list, QueueManager.getCurrent(this))

        val emptyBox = findViewById<View>(R.id.emptyBox)
        val recycler = findViewById<View>(R.id.recycler)
        val tvCount = findViewById<TextView>(R.id.tvCount)
        val tvClear = findViewById<TextView>(R.id.tvClear)

        if (list.isEmpty()) {
            emptyBox.visibility = View.VISIBLE
            recycler.visibility = View.GONE
            tvCount.text = "0개"
            tvClear.visibility = View.GONE
        } else {
            emptyBox.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            tvCount.text = "${list.size}개"
            tvClear.visibility = View.VISIBLE
        }
    }
}
