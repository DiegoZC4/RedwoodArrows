package org.redwoodarrows;

/** Axis-locked paging, with UI.md's continuous drag/nearest snap.
 * Pure motion only: browsing never writes the selected value. */
final class CyclicPagerMotion {
    static final int X=1,Y=2;
    final int count;final float jitter,lock;final boolean horizontalOnly;
    int selected,axis=Y,lockedAxis,steps;
    float offset,width,height,startX,startY;
    boolean held,moved,yielded;
    CyclicPagerMotion(int count,int selected,float jitter,float lock){
        this(count,selected,jitter,lock,false);
    }
    CyclicPagerMotion(int count,int selected,float jitter,float lock,boolean horizontalOnly){
        if(count<1||selected < -1||selected>=count)throw new IllegalArgumentException("Invalid pager values");
        this.count=count;this.selected=selected;this.jitter=jitter;this.lock=lock;this.horizontalOnly=horizontalOnly;axis=horizontalOnly?X:Y;
    }
    int wrap(int n){return Math.floorMod(n,count);}
    // -1 is an honest Unassigned placeholder, not an extra shooter in the loop.
    int at(int relative){if(selected>=0)return wrap(selected+relative);return relative==0?-1:wrap(relative>0?relative-1:relative);}
    void begin(float x,float y,float width,float height){
        this.width=Math.max(1,width);this.height=Math.max(1,height);startX=x;startY=y;
        held=true;moved=yielded=false;lockedAxis=0;axis=horizontalOnly?X:Y;steps=0;offset=0;
    }
    void move(float x,float y){
        if(!held||yielded)return;float dx=x-startX,dy=y-startY;
        if(Math.hypot(dx,dy)>jitter)moved=true;
        if(horizontalOnly){
            axis=X;
            // Until a clear horizontal gesture wins, scrolling has priority.
            // Once yielded, never turn the remainder of a scroll into a rename.
            if(lockedAxis==0&&Math.max(Math.abs(dx),Math.abs(dy))>lock){
                if(Math.abs(dx)>Math.abs(dy)*1.25f)lockedAxis=X;
                else{lockedAxis=Y;yielded=true;steps=0;offset=0;return;}
            }
            if(lockedAxis==0&&Math.abs(dx)<=Math.abs(dy)*1.25f){steps=0;offset=0;return;}
        }else{
            if(lockedAxis==0&&Math.max(Math.abs(dx),Math.abs(dy))>lock)lockedAxis=Math.abs(dx)>=Math.abs(dy)?X:Y;
            axis=lockedAxis!=0?lockedAxis:(Math.abs(dx)>Math.abs(dy)?X:Y);
        }
        float delta=axis==X?dx:dy,extent=extent();
        steps=(int)(-delta/extent);offset=delta+steps*extent;
    }
    float extent(){return axis==X?width:height;}
    int release(float x,float y){
        move(x,y);held=false;
        if(yielded){steps=0;offset=0;return 0;}
        if(!moved){axis=horizontalOnly?X:Y;steps=0;offset=0;return 1;}
        return Math.abs(offset)>extent()/2?(offset<0?1:-1):0;
    }
    void rest(){held=yielded=false;steps=0;offset=0;}
}
