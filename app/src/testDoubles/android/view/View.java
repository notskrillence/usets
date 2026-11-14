package android.view;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import java.util.ArrayList;
/** Minimal execution double: does not verify Android rendering or event dispatch. */
public class View {
 private final Context context;private int width,height;private final ArrayList<Runnable> posted=new ArrayList<Runnable>();
 public View(Context c){context=c;}public Resources getResources(){return context.getResources();}
 public void setBackgroundColor(int c){}public void setContentDescription(CharSequence c){}public void setFocusable(boolean b){}public void setMinimumHeight(int h){}
 public int getWidth(){return width;}public int getHeight(){return height;}
 public void layout(int l,int t,int r,int b){int oldw=width,oldh=height;width=r-l;height=b-t;onSizeChanged(width,height,oldw,oldh);}
 protected void onSizeChanged(int w,int h,int oldw,int oldh){}protected void onAttachedToWindow(){}protected void onDetachedFromWindow(){}
 protected void onDraw(Canvas c){}public void invalidate(){}public void postInvalidateOnAnimation(){}
 public boolean postDelayed(Runnable r,long delay){posted.add(r);return true;}public boolean removeCallbacks(Runnable r){while(posted.remove(r)){}return true;}
 public boolean onTouchEvent(MotionEvent e){return false;}
 public void attachForTest(){onAttachedToWindow();}public void detachForTest(){onDetachedFromWindow();}
 public boolean runOneCallbackForTest(){if(posted.isEmpty())return false;posted.remove(0).run();return true;}
 public int callbackCountForTest(){return posted.size();}public void drawForTest(Canvas c){onDraw(c);}
}
