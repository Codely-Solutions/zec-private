package zec.uibridge;
import android.app.*;
import android.os.*;
import android.graphics.Rect;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

public final class Bridge extends Instrumentation {
 private static final String TARGET="com.personal.zecprivate.foss.debug";
 private boolean running=true;
 @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
 @Override public void onStart() {
  try {
   if(!TARGET.equals(getTargetContext().getPackageName()))throw new SecurityException();
   getTargetContext().startActivity(getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
   try(ServerSocket server=new ServerSocket()) {
    server.bind(new InetSocketAddress("127.0.0.1",8777)); server.setSoTimeout(2000);
    long deadline=SystemClock.elapsedRealtime()+1000000;
    while(running && SystemClock.elapsedRealtime()<deadline) {
     try(Socket socket=server.accept()) {
      socket.setSoTimeout(10000);
      BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
      String line=in.readLine(); if(line==null || line.length()>8192) continue;
      JSONObject response;
      try { response=execute(new JSONObject(line)); }
      catch(Exception e) { response=new JSONObject().put("error",e.getClass().getSimpleName()); }
      BufferedWriter out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),"UTF-8"));out.write(response.toString()+"\n");out.flush();
     } catch(SocketTimeoutException ignored) { }
    }
   }
   finish(Activity.RESULT_OK,new Bundle());
  } catch(Exception e) { Bundle b=new Bundle();b.putString("stream",e.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,b); }
 }
 private List<AccessibilityNodeInfo> nodes() {
  List<AccessibilityNodeInfo> all=new ArrayList<>(); AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
  if(root!=null && !TARGET.contentEquals(root.getPackageName()))throw new SecurityException();
  if(root!=null) collect(root,all);return all;
 }
 private void collect(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> all) {
  if(all.size()>600) return;all.add(node);
  for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)collect(child,all);}
 }
 private String text(AccessibilityNodeInfo n) { return n.getText()!=null?n.getText().toString():(n.getContentDescription()!=null?n.getContentDescription().toString():""); }
 private JSONObject snapshot() throws Exception {
  JSONArray data=new JSONArray();
  for(AccessibilityNodeInfo n:nodes()) {
   Rect b=new Rect();n.getBoundsInScreen(b);
   if(!text(n).isEmpty() || n.isClickable() || n.isEditable()) data.put(new JSONObject().put("text",text(n)).put("id",n.getViewIdResourceName()).put("bounds",b.flattenToString()).put("clickable",n.isClickable()).put("enabled",n.isEnabled()).put("editable",n.isEditable()).put("checked",n.isChecked()).put("package",String.valueOf(n.getPackageName())));
  }
  return new JSONObject().put("nodes",data);
 }
 private boolean acceptance(AccessibilityNodeInfo n) {
  String label=text(n).toLowerCase(java.util.Locale.ROOT);
  if(label.contains("confirm") || label.contains("accept"))return true;
  for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);if(child!=null && acceptance(child))return true;}
  return false;
 }
 private void tap(int x,int y) {
  long t=SystemClock.uptimeMillis();
  MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0);getUiAutomation().injectInputEvent(down,true);down.recycle();
  SystemClock.sleep(70);MotionEvent up=MotionEvent.obtain(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0);getUiAutomation().injectInputEvent(up,true);up.recycle();
 }
 private JSONObject execute(JSONObject c) throws Exception {
  String action=c.getString("action");
  if(action.equals("finish")){running=false;return new JSONObject().put("finished",true);}
  if(action.equals("click_text")) {
   String wanted=c.getString("text"); if(wanted.toLowerCase(java.util.Locale.ROOT).contains("confirm") || wanted.toLowerCase(java.util.Locale.ROOT).contains("accept"))throw new IllegalArgumentException("Accepting quotes forbidden");
   AccessibilityNodeInfo found=null;
   for(AccessibilityNodeInfo n:nodes())if(n.isVisibleToUser() && text(n).equalsIgnoreCase(wanted)){found=n;break;}
   if(found==null)throw new IllegalArgumentException("Text not visible");
   while(found!=null && !found.isClickable())found=found.getParent();
   if(found==null || !found.isEnabled() || acceptance(found))throw new IllegalArgumentException("Control disabled");
   Rect b=new Rect();found.getBoundsInScreen(b);tap(b.centerX(),b.centerY());
  } else if(action.equals("set_text")) {
   int index=c.optInt("index",0);String value=c.getString("value");if(!value.matches("[A-Za-z0-9._]{1,160}"))throw new IllegalArgumentException();
   List<AccessibilityNodeInfo> editable=new ArrayList<>();for(AccessibilityNodeInfo n:nodes())if(n.isEditable())editable.add(n);
   Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
   if(!editable.get(index).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))throw new IllegalStateException();
  } else if(action.equals("back"))sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
  else if(action.equals("swipe")) {
   int x=c.getInt("x"),y1=c.getInt("y1"),y2=c.getInt("y2");
   if(x<0 || x>1080 || y1<0 || y2>1920 || y2-y1<300)throw new SecurityException();
   List<AccessibilityNodeInfo> current=nodes();int titleTop=-1,acceptTop=1920;
   for(AccessibilityNodeInfo n:current){Rect b=new Rect();n.getBoundsInScreen(b);if(text(n).equalsIgnoreCase("Review Quote"))titleTop=b.top;if(text(n).toLowerCase(java.util.Locale.ROOT).contains("confirm"))acceptTop=Math.min(acceptTop,b.top);}
   if(titleTop<0 || y1>=titleTop || y2>=acceptTop-20)throw new SecurityException();
   long t=SystemClock.uptimeMillis();
   for(int i=0;i<=25;i++){int mode=i==0?MotionEvent.ACTION_DOWN:(i==25?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE);MotionEvent event=MotionEvent.obtain(t,SystemClock.uptimeMillis(),mode,x,y1+(y2-y1)*i/25,0);getUiAutomation().injectInputEvent(event,true);event.recycle();SystemClock.sleep(20);}
  } else if(action.equals("scroll")) {
   boolean done=false;for(AccessibilityNodeInfo n:nodes())if(n.isScrollable()){done=n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);if(done)break;}
   if(!done)throw new IllegalStateException();
  } else if(!action.equals("snapshot"))throw new IllegalArgumentException("Unsupported action");
  SystemClock.sleep(700);return snapshot();
 }
}
