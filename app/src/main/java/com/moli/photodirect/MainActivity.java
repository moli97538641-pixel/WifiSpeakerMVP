package com.moli.photodirect;
import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.*;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public class MainActivity extends Activity {
  private TextureView pv;
  private FrameLayout holder;
  private EditText host,port,key;
  private TextView info;
  private Button shutter;
  private CameraDevice camera;
  private CameraCaptureSession session;
  private CaptureRequest.Builder previewRequest;
  private ImageReader reader;
  private Surface previewSurface;
  private HandlerThread worker;
  private Handler handler;
  private Size imageSize, previewSize;
  private Rect sensorRect;
  private int sensorRotation=90, maxAF=0, maxAE=0, focusMode=CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
  private boolean active=false;
  private final AtomicBoolean busy=new AtomicBoolean(false);
  private String savedEndpoint="",savedKey="";
  private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
  private TextView text(String s,int sz){TextView t=new TextView(this);t.setText(s);t.setTextSize(sz);return t;}
  private void status(String s){runOnUiThread(()->info.setText(s));}
  private EditText field(LinearLayout row,String hint,String value,int type){
    EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setSingleLine(true);e.setInputType(type);
    row.addView(e,new LinearLayout.LayoutParams(-1,dp(48)));return e;
  }
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(9),dp(12),dp(9));setContentView(root);
    root.addView(text("PhotoDirect v1.2 · 原图直传电脑",18));
    LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);root.addView(row);
    android.content.SharedPreferences sp=getSharedPreferences("net",0);
    host=field(row,"电脑 IP",sp.getString("host","192.168.1.100"),1);
    port=field(row,"端口",sp.getString("port","8765"),2);
    key=field(row,"32 位配对密钥",sp.getString("key",""),1);
    Button check=new Button(this);check.setText("测试电脑连接");root.addView(check,new LinearLayout.LayoutParams(-1,dp(45)));
    info=text("初始化相机…",13);root.addView(info,new LinearLayout.LayoutParams(-1,dp(45)));
    holder=new FrameLayout(this);holder.setBackgroundColor(0xff000000);root.addView(holder,new LinearLayout.LayoutParams(-1,0,1));
    pv=new TextureView(this);holder.addView(pv,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
    pv.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_UP)focusAt(e.getX(),e.getY());return true;});
    pv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
      public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){startCamera();}
      public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){applyTransform(w,h);}
      public boolean onSurfaceTextureDestroyed(SurfaceTexture t){closeCamera();return true;}
      public void onSurfaceTextureUpdated(SurfaceTexture t){}
    });
    shutter=new Button(this);shutter.setText("拍照并直接保存到电脑");shutter.setEnabled(false);
    root.addView(shutter,new LinearLayout.LayoutParams(-1,dp(62)));
    root.addView(text("点按画面选定对焦位置 · 照片不存手机",12));
    shutter.setOnClickListener(v->shoot());
    check.setOnClickListener(v->{storeConfig();new Thread(()->{HttpURLConnection c=null;try{
      c=openConnection("/pair","GET");int code=c.getResponseCode();
      status(code==200?"电脑已连接":"连接失败：HTTP "+code);
    }catch(Exception ex){status("连接失败："+ex.getMessage());}finally{if(c!=null)c.disconnect();}}).start();});
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{Manifest.permission.CAMERA},14);
  }
  private void storeConfig(){
    getSharedPreferences("net",0).edit().putString("host",host.getText().toString().trim())
      .putString("port",port.getText().toString().trim()).putString("key",key.getText().toString().trim()).apply();
  }
  private HttpURLConnection openConnection(String path,String method)throws Exception{
    android.content.SharedPreferences p=getSharedPreferences("net",0);
    String h=p.getString("host",""),pt=p.getString("port","8765");
    int portNum=Integer.parseInt(pt);
    if(!h.matches("[A-Za-z0-9.-]+")||portNum<1||portNum>65535)throw new IOException("IP或端口不正确");
    HttpURLConnection c=(HttpURLConnection)new URL("http://"+h+":"+portNum+path).openConnection();
    c.setRequestMethod(method);c.setConnectTimeout(5000);c.setReadTimeout(60000);c.setUseCaches(false);
    c.setRequestProperty("X-Upload-Key",p.getString("key",""));return c;
  }
  @Override public void onRequestPermissionsResult(int n,String[] p,int[] r){
    super.onRequestPermissionsResult(n,p,r);if(n==14&&r.length>0&&r[0]==PackageManager.PERMISSION_GRANTED)startCamera();else status("未获得摄像头权限");
  }
  @Override protected void onResume(){super.onResume();active=true;worker=new HandlerThread("PhotoDirectCamera");worker.start();handler=new Handler(worker.getLooper());if(pv!=null&&pv.isAvailable())startCamera();}
  @Override protected void onPause(){active=false;closeCamera();if(worker!=null){worker.quitSafely();worker=null;}handler=null;super.onPause();}
  private static double aspect(Size s){return (double)Math.max(s.getWidth(),s.getHeight())/Math.min(s.getWidth(),s.getHeight());}
  private static boolean has(int[] a,int n){if(a!=null)for(int v:a)if(v==n)return true;return false;}
  private Size matchPreview(Size[] all,Size jpeg){
    Size best=null;double score=Double.MAX_VALUE;
    for(Size s:all){int w=Math.max(s.getWidth(),s.getHeight());
      double error=Math.abs(aspect(s)-aspect(jpeg))*1_000_000;
      double bias=Math.abs(w-1280)/1280.0+(w>1920?2000:0);
      if(error+bias<score){score=error+bias;best=s;}
    }return best==null?all[0]:best;
  }
  private void startCamera(){
    if(!active||handler==null||!pv.isAvailable()||camera!=null)return;
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
    try {
      CameraManager mgr=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
      CameraCharacteristics ch=null;String camId=null;
      for(String id:mgr.getCameraIdList()){CameraCharacteristics candidate=mgr.getCameraCharacteristics(id);
        if(Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(candidate.get(CameraCharacteristics.LENS_FACING))){ch=candidate;camId=id;break;}}
      if(ch==null)throw new IOException("未找到后置摄像头");
      Integer rotate=ch.get(CameraCharacteristics.SENSOR_ORIENTATION);if(rotate!=null)sensorRotation=rotate;
      sensorRect=ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
      Integer af=ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF),ae=ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
      maxAF=af==null?0:af;maxAE=ae==null?0:ae;
      int[] modes=ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
      if(!has(modes,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
        focusMode=has(modes,CaptureRequest.CONTROL_AF_MODE_AUTO)?CaptureRequest.CONTROL_AF_MODE_AUTO:CaptureRequest.CONTROL_AF_MODE_OFF;
      StreamConfigurationMap map=ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      if(map==null)throw new IOException("相机不支持 Camera2 配置查询");
      Size[] jpegs=map.getOutputSizes(android.graphics.ImageFormat.JPEG);
      Size[] previews=map.getOutputSizes(SurfaceTexture.class);
      if(jpegs==null||previews==null||jpegs.length==0||previews.length==0)throw new IOException("相机不支持 JPEG/预览");
      imageSize=Collections.max(Arrays.asList(jpegs),Comparator.comparingLong(s->(long)s.getWidth()*s.getHeight()));
      previewSize=matchPreview(previews,imageSize);
      if(Math.abs(aspect(previewSize)-aspect(imageSize))>0.012){
        Size backup=null;long bestArea=0;
        for(Size s:jpegs)if(Math.abs(aspect(s)-aspect(previewSize))<=0.012){
          long area=(long)s.getWidth()*s.getHeight();if(area>bestArea){backup=s;bestArea=area;}}
        if(backup!=null)imageSize=backup;
      }
      reader=ImageReader.newInstance(imageSize.getWidth(),imageSize.getHeight(),android.graphics.ImageFormat.JPEG,2);
      reader.setOnImageAvailableListener(ir->{Image frame=null;byte[] bytes=null;try{
        frame=ir.acquireNextImage();if(frame==null)return;
        ByteBuffer buf=frame.getPlanes()[0].getBuffer();bytes=new byte[buf.remaining()];buf.get(bytes);
      }catch(Exception ex){error("JPEG 读取失败："+ex.getMessage());return;}
      finally{if(frame!=null)frame.close();}
      if(!busy.get()){Arrays.fill(bytes,(byte)0);return;}
      final byte[] photo=bytes;new Thread(()->upload(photo)).start();
      },handler);
      pv.getSurfaceTexture().setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());
      updateViewport();
      mgr.openCamera(camId,new CameraDevice.StateCallback(){
        public void onOpened(CameraDevice c){if(!active){c.close();return;}camera=c;configureSession();}
        public void onDisconnected(CameraDevice c){c.close();camera=null;error("摄像头断开");}
        public void onError(CameraDevice c,int e){c.close();camera=null;error("相机错误："+e);}
      },handler);
    }catch(Exception ex){status("打开相机失败："+ex.getMessage());}
  }
  private void updateViewport(){
    holder.post(()->{
      if(holder==null||previewSize==null)return;
      int availW=holder.getWidth(),availH=holder.getHeight();
      if(availW<=0||availH<=0)return;
      // Height and width follow the *rotated* 4:3 or 16:9 native preview, not screen size.
      double widthPerHeight=1/aspect(previewSize);
      int width=Math.min(availW,(int)Math.round(availH*widthPerHeight));
      int height=(int)Math.round(width/widthPerHeight);
      if(height>availH){height=availH;width=(int)Math.round(height*widthPerHeight);}
      pv.setLayoutParams(new FrameLayout.LayoutParams(width,height,Gravity.CENTER));
      pv.post(()->applyTransform(pv.getWidth(),pv.getHeight()));
    });
  }
  private void applyTransform(int w,int h){
    if(previewSize==null||w<=0||h<=0)return;
    int rotation=getWindowManager().getDefaultDisplay().getRotation();
    Matrix m=new Matrix();RectF vr=new RectF(0,0,w,h);
    if(rotation==Surface.ROTATION_90||rotation==Surface.ROTATION_270){
      RectF br=new RectF(0,0,previewSize.getHeight(),previewSize.getWidth());
      float cx=vr.centerX(),cy=vr.centerY();
      br.offset(cx-br.centerX(),cy-br.centerY());m.setRectToRect(vr,br,Matrix.ScaleToFit.FILL);
      float scale=Math.max((float)h/previewSize.getHeight(),(float)w/previewSize.getWidth());
      m.postScale(scale,scale,cx,cy);m.postRotate(90*(rotation-2),cx,cy);
    } else if(rotation==Surface.ROTATION_180){m.postRotate(180,w/2f,h/2f);}
    pv.setTransform(m);
  }
  private void configureSession(){
    try {
      if(camera==null||reader==null)return;
      previewSurface=new Surface(pv.getSurfaceTexture());
      previewRequest=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      previewRequest.addTarget(previewSurface);
      previewRequest.set(CaptureRequest.CONTROL_AF_MODE,focusMode);
      previewRequest.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
      previewRequest.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
      camera.createCaptureSession(Arrays.asList(previewSurface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
        public void onConfigured(CameraCaptureSession s){session=s;try{
          s.setRepeatingRequest(previewRequest.build(),null,handler);
          runOnUiThread(()->shutter.setEnabled(true));status("相机已就绪，点击画面可对焦");
        }catch(Exception ex){status("预览失败："+ex.getMessage());}}
        public void onConfigureFailed(CameraCaptureSession s){status("相机不支持当前图片尺寸组合");}
      },handler);
    }catch(Exception ex){status("预览异常："+ex.getMessage());}
  }
  private void focusAt(float x,float y){
    if(session==null||previewRequest==null||handler==null||sensorRect==null)return;
    float nx=Math.max(0,Math.min(1,x/Math.max(1,pv.getWidth())));
    float ny=Math.max(0,Math.min(1,y/Math.max(1,pv.getHeight())));
    float sx=sensorRotation==270?1-ny:ny;
    float sy=sensorRotation==270?nx:1-nx;
    int cx=sensorRect.left+(int)(sx*sensorRect.width()),cy=sensorRect.top+(int)(sy*sensorRect.height());
    int rw=Math.max(1,sensorRect.width()/10),rh=Math.max(1,sensorRect.height()/10);
    int left=Math.max(sensorRect.left,Math.min(sensorRect.right-rw,cx-rw/2));
    int top=Math.max(sensorRect.top,Math.min(sensorRect.bottom-rh,cy-rh/2));
    MeteringRectangle area=new MeteringRectangle(left,top,rw,rh,MeteringRectangle.METERING_WEIGHT_MAX);
    handler.post(()->{try{
      if(maxAF>0)previewRequest.set(CaptureRequest.CONTROL_AF_REGIONS,new MeteringRectangle[]{area});
      if(maxAE>0)previewRequest.set(CaptureRequest.CONTROL_AE_REGIONS,new MeteringRectangle[]{area});
      if(focusMode!=CaptureRequest.CONTROL_AF_MODE_OFF){
        previewRequest.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_AUTO);
        previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        session.capture(previewRequest.build(),null,handler);
        previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
        session.capture(previewRequest.build(),null,handler);
        previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
      }
      session.setRepeatingRequest(previewRequest.build(),null,handler);
      status("已设置对焦点");
    }catch(Exception ex){status("点击对焦失败："+ex.getMessage());}});
  }
  private void shoot(){
    if(camera==null||session==null||reader==null){status("摄像头尚未就绪");return;}
    storeConfig();
    if(!busy.compareAndSet(false,true))return;
    runOnUiThread(()->shutter.setEnabled(false));
    status("正在对焦并拍照…");
    handler.post(()->{try{
      if(focusMode!=CaptureRequest.CONTROL_AF_MODE_OFF&&previewRequest!=null){
        previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
        session.capture(previewRequest.build(),null,handler);
        previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        session.setRepeatingRequest(previewRequest.build(),null,handler);
      }
    }catch(Exception ignored){}});
    handler.postDelayed(()->{try{
      if(!busy.get()||camera==null||session==null)return;
      CaptureRequest.Builder req=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
      req.addTarget(reader.getSurface());
      req.set(CaptureRequest.CONTROL_AF_MODE,focusMode);
      req.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
      req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
      req.set(CaptureRequest.JPEG_QUALITY,(byte)100);
      req.set(CaptureRequest.JPEG_ORIENTATION,sensorRotation);
      if(previewRequest!=null){
        MeteringRectangle[] af=previewRequest.get(CaptureRequest.CONTROL_AF_REGIONS);
        MeteringRectangle[] ae=previewRequest.get(CaptureRequest.CONTROL_AE_REGIONS);
        if(af!=null)req.set(CaptureRequest.CONTROL_AF_REGIONS,af);
        if(ae!=null)req.set(CaptureRequest.CONTROL_AE_REGIONS,ae);
      }
      session.capture(req.build(),new CameraCaptureSession.CaptureCallback(){
        @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f){error("拍摄失败，照片未保存");}
      },handler);
    }catch(Exception ex){error("拍摄失败："+ex.getMessage());}},600);
  }
  private void upload(byte[] bytes){
    HttpURLConnection c=null;
    try {
      status("正在传输原图 "+bytes.length+" 字节");
      MessageDigest md=MessageDigest.getInstance("SHA-256");
      byte[] sha=md.digest(bytes);StringBuilder hash=new StringBuilder();
      for(byte v:sha)hash.append(String.format(java.util.Locale.US,"%02x",v&255));
      c=openConnection("/photo","POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","image/jpeg");
      c.setFixedLengthStreamingMode(bytes.length);
      try(OutputStream out=c.getOutputStream()){out.write(bytes);}
      if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
      String response;try(InputStream in=c.getInputStream()){response=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
      JSONObject obj=new JSONObject(response);
      if(!obj.optBoolean("ok")||obj.optLong("bytes")!=bytes.length||!hash.toString().equalsIgnoreCase(obj.optString("sha256")))
        throw new IOException("电脑确认信息或 SHA-256 不匹配");
      status("电脑已保存："+obj.optString("filename"));
    }catch(Exception ex){status("传输失败，手机未保存："+ex.getMessage());}
    finally{if(c!=null)c.disconnect();Arrays.fill(bytes,(byte)0);busy.set(false);runOnUiThread(()->shutter.setEnabled(camera!=null&&session!=null));}
  }
  private void error(String msg){busy.set(false);status(msg);runOnUiThread(()->shutter.setEnabled(camera!=null&&session!=null));}
  private void closeCamera(){
    if(session!=null){session.close();session=null;}
    if(camera!=null){camera.close();camera=null;}
    if(reader!=null){reader.close();reader=null;}
    if(previewSurface!=null){previewSurface.release();previewSurface=null;}
    previewRequest=null;busy.set(false);
    if(shutter!=null)runOnUiThread(()->shutter.setEnabled(false));
  }
}
