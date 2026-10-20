package app.yoru;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Rational;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import org.json.*;
import java.util.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView view;
    private LinearLayout toolbar;
    private long position;
    private String url,title,progressKey;
    private boolean playing=true,hls=false;
    private final Map<String,String> headers=new HashMap<>();
    private final List<MediaItem.SubtitleConfiguration> subtitles=new ArrayList<>();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        title=getIntent().getStringExtra("title");progressKey=getIntent().getStringExtra("progressKey");
        position=state!=null?state.getLong("position"):getIntent().getLongExtra("position",0);
        try{
            JSONObject stream=new JSONObject(getIntent().getStringExtra("stream"));url=stream.getString("url");hls=stream.optBoolean("hls");
            if(!Arrays.asList("http","https").contains(Uri.parse(url).getScheme()))throw new IllegalArgumentException("Invalid stream URL");
            JSONObject values=stream.optJSONObject("headers");if(values!=null)for(Iterator<String> keys=values.keys();keys.hasNext();){String key=keys.next();headers.put(key,values.getString(key));}
            JSONArray tracks=stream.optJSONArray("subtitles");if(tracks!=null)for(int i=0;i<tracks.length();i++){JSONObject track=tracks.getJSONObject(i);addSubtitle(track.getString("file"),track.optString("label","Subtitles"));}
        }catch(Exception error){Toast.makeText(this,"Invalid playback request: "+error.getMessage(),Toast.LENGTH_LONG).show();finish();return;}
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);view=new PlayerView(this);view.setShowSubtitleButton(true);view.setShowNextButton(false);view.setShowPreviousButton(false);view.setControllerShowTimeoutMs(4000);frame.addView(view,new FrameLayout.LayoutParams(-1,-1));
        toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(14,8,14,8);toolbar.setBackgroundColor(0xB00B0911);
        Button back=button("‹",v->finish());toolbar.addView(back,new LinearLayout.LayoutParams(55,52));
        TextView text=new TextView(this);text.setText(title);text.setTextColor(Color.WHITE);text.setTextSize(13);text.setMaxLines(2);toolbar.addView(text,new LinearLayout.LayoutParams(0,52,1));
        toolbar.addView(button("CC +",v->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");startActivityForResult(intent,41);}));
        toolbar.addView(button("Fit",v->{int mode=view.getResizeMode()==AspectRatioFrameLayout.RESIZE_MODE_FIT?AspectRatioFrameLayout.RESIZE_MODE_ZOOM:AspectRatioFrameLayout.RESIZE_MODE_FIT;view.setResizeMode(mode);((Button)v).setText(mode==AspectRatioFrameLayout.RESIZE_MODE_FIT?"Fit":"Fill");}));
        toolbar.addView(button("↻",v->setRequestedOrientation(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)));
        frame.addView(toolbar,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->toolbar.setVisibility(visibility));setContentView(frame);
        frame.setOnApplyWindowInsetsListener((v,insets)->{toolbar.setPadding(insets.getSystemWindowInsetLeft()+14,insets.getSystemWindowInsetTop()+8,insets.getSystemWindowInsetRight()+14,8);return insets;});
    }
    private Button button(String label,View.OnClickListener listener){Button button=new Button(this);button.setText(label);button.setTextSize(11);button.setTextColor(0xFFE5D5FF);button.setBackgroundColor(Color.TRANSPARENT);button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(10,4,10,4);button.setOnClickListener(listener);return button;}
    private void addSubtitle(String url,String label){String display=url; if(url.startsWith("content:")){try(android.database.Cursor cursor=getContentResolver().query(Uri.parse(url),new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())display=cursor.getString(0);}catch(Exception ignored){}}String lower=display.toLowerCase(Locale.ROOT);String mime=lower.contains(".srt")?MimeTypes.APPLICATION_SUBRIP:lower.contains(".ass")||lower.contains(".ssa")?MimeTypes.TEXT_SSA:MimeTypes.TEXT_VTT;subtitles.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(mime).setLabel(label).setLanguage(label).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());}
    private MediaItem media(){MediaItem.Builder item=new MediaItem.Builder().setUri(url).setSubtitleConfigurations(subtitles).setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());if(hls)item.setMimeType(MimeTypes.APPLICATION_M3U8);return item.build();}
    @Override protected void onStart(){super.onStart();if(view==null)return;
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).setConnectTimeoutMs(25000).setReadTimeoutMs(45000).setAllowCrossProtocolRedirects(false);
        player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(this,http))).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);player.setHandleAudioBecomingNoisy(true);view.setPlayer(player);
        player.addListener(new Player.Listener(){@Override public void onIsPlayingChanged(boolean isPlaying){updatePip();}@Override public void onPlayerError(PlaybackException error){if(isFinishing())return;new android.app.AlertDialog.Builder(PlayerActivity.this).setTitle("Playback interrupted").setMessage(error.getErrorCodeName()+"\nThe source may have expired or the format may be unsupported.").setPositiveButton("Retry",(d,w)->{if(player!=null){player.prepare();player.play();}}).setNegativeButton("Back to sources",(d,w)->finish()).show();}});
        player.setMediaItem(media());player.seekTo(position);player.prepare();player.setPlayWhenReady(playing);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==41&&result==RESULT_OK&&data!=null&&data.getData()!=null){try{getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}addSubtitle(data.getData().toString(),"Local subtitles");if(player!=null){long at=player.getCurrentPosition();player.setMediaItem(media(),at);player.prepare();player.play();}}}
    private void updatePip(){if(view==null||!getPackageManager().hasSystemFeature("android.software.picture_in_picture"))return;android.graphics.Rect rect=new android.graphics.Rect();view.getGlobalVisibleRect(rect);PictureInPictureParams.Builder params=new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9));if(!rect.isEmpty())params.setSourceRectHint(rect);if(android.os.Build.VERSION.SDK_INT>=31)params.setAutoEnterEnabled(player!=null&&player.isPlaying());setPictureInPictureParams(params.build());}
    @Override protected void onUserLeaveHint(){super.onUserLeaveHint();if(player!=null&&player.isPlaying()&&getPackageManager().hasSystemFeature("android.software.picture_in_picture")){try{enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9)).build());}catch(IllegalStateException ignored){}}}
    @Override public void onPictureInPictureModeChanged(boolean pip,Configuration configuration){super.onPictureInPictureModeChanged(pip,configuration);if(view!=null)view.setUseController(!pip);if(toolbar!=null)toolbar.setVisibility(pip?View.GONE:View.VISIBLE);}
    private void remember(){if(player!=null){position=player.getCurrentPosition();playing=player.getPlayWhenReady();}if(progressKey!=null)getSharedPreferences("player_progress",MODE_PRIVATE).edit().putLong(progressKey,position).apply();}
    @Override public void finish(){remember();super.finish();}
    @Override protected void onSaveInstanceState(Bundle out){remember();out.putLong("position",position);super.onSaveInstanceState(out);}
    @Override protected void onStop(){remember();if(player!=null){view.setPlayer(null);player.release();player=null;}super.onStop();}
}
