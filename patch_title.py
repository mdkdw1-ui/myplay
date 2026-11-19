import re

# 1) Service: 결과를 받아 prefs 저장 + 브로드캐스트
p='app/src/main/java/com/example/myplayer/ChapterReidentifyService.kt'
s=open(p,encoding='utf-8').read()
old='ChapterReidentifyClient.reidentify(vid, pos / 1000.0, parsed)'
new='''val r = ChapterReidentifyClient.reidentify(vid, pos / 1000.0, parsed)
            if (r.ok && r.title.isNotBlank()) {
                prefs.edit().putString("current_title", r.title).apply()
                val i = android.content.Intent("com.example.myplayer.ACTION_CHAPTER_TITLE_UPDATED").setPackage(packageName)
                i.putExtra("title", r.title)
                sendBroadcast(i)
                android.util.Log.d("ChapterReidentify", "title updated: ${r.title}")
            }'''
assert old in s, 'service anchor not found'
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print('service patched')

# 2) Activity: receiver 속성 추가
p2='app/src/main/java/com/example/myplayer/AudioPlayerActivity.kt'
a=open(p2,encoding='utf-8').read()
recv='''    private val titleUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            val t = intent.getStringExtra("title") ?: return
            currentTitle = t
            pref?.edit()?.putString("current_title", t)?.apply()
            runOnUiThread {
                findViewById<android.widget.TextView>(R.id.tvTitle)?.text = t
                refreshMediaMetadata()
            }
        }
    }

'''
assert 'override fun onCreate' in a, 'onCreate anchor not found'
a=a.replace('override fun onCreate', recv+'override fun onCreate',1)

# 3) receiver 등록 (skipReceiver 등록 뒤)
m=re.search(r'registerReceiver\(skipReceiver, filter\)\s*\n\s*\}', a)
assert m, 'skipReceiver anchor not found'
reg='''
            val titleFilter = android.content.IntentFilter("com.example.myplayer.ACTION_CHAPTER_TITLE_UPDATED")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(titleUpdateReceiver, titleFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(titleUpdateReceiver, titleFilter)
            }'''
a=a[:m.end()]+reg+a[m.end():]

# 4) onDestroy 해제
idx=a.find('override fun onDestroy')
assert idx>0, 'onDestroy not found'
j=a.find('super.onDestroy()', idx)
assert j>0, 'super.onDestroy not found'
a=a[:j]+'try { unregisterReceiver(titleUpdateReceiver) } catch (_: Exception) {}\n        '+a[j:]
open(p2,'w',encoding='utf-8').write(a)
print('activity patched')
