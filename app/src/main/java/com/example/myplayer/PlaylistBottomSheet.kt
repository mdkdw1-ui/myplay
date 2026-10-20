package com.example.myplayer

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PlaylistBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CURRENT_ID = "current_id"
        private const val ARG_CURRENT_TITLE = "current_title"
        private const val ARG_ITEMS = "items_json"

        fun newInstance(currentId: String, currentTitle: String, items: List<HomeVideo>): PlaylistBottomSheet {
            val f = PlaylistBottomSheet()
            val args = Bundle()
            args.putString(ARG_CURRENT_ID, currentId)
            args.putString(ARG_CURRENT_TITLE, currentTitle)
            val arr = org.json.JSONArray()
            items.forEach { v ->
                val obj = org.json.JSONObject()
                obj.put("videoId", v.videoId)
                obj.put("title", v.title)
                obj.put("channel", v.channel)
                obj.put("thumbnail", v.thumbnail)
                arr.put(obj)
            }
            args.putString(ARG_ITEMS, arr.toString())
            f.arguments = args
            return f
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_playlist, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentId = arguments?.getString(ARG_CURRENT_ID) ?: ""
        val currentTitle = arguments?.getString(ARG_CURRENT_TITLE) ?: ""
        val itemsJson = arguments?.getString(ARG_ITEMS) ?: "[]"

        val items = mutableListOf<HomeVideo>()
        try {
            val arr = org.json.JSONArray(itemsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("videoId", "")
                if (id.isBlank()) continue
                items.add(HomeVideo(
                    videoId = id,
                    title = obj.optString("title", ""),
                    channel = obj.optString("channel", ""),
                    thumbnail = obj.optString("thumbnail", "")
                ))
            }
        } catch (_: Exception) {}

        view.findViewById<TextView>(R.id.tvCurrentSong)?.text = "▶ $currentTitle"

        val rv = view.findViewById<RecyclerView>(R.id.rvPlaylist)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = QueueAdapter(
            onClick = { _, video ->
                val act = activity as? AudioPlayerActivity
                act?.jumpToSong(video)
                dismiss()
            },
            onRemove = { video ->
                QueueManager.remove(requireContext(), video.videoId)
                dismiss()
            },
            onMoveTop = { video ->
                QueueManager.moveToTop(requireContext(), video.videoId)
                dismiss()
            }
        ).also { a ->
            a.submit(items, currentId)
        }
    }
}
