package org.redwoodarrows;

import java.util.*;

/** Photo-by-photo order for stepping through reviews: the Photos list as filtered, newest first. Navigation only; never edits or certifies. */
final class ReviewQueue {
    static final class Item {final Store.Group group;final int view;Item(Store.Group group,int view){this.group=group;this.view=view;}String photo(){return group.views.get(view).id;}}
    final List<Item> items=new ArrayList<>();
    ReviewQueue(List<Store.Group> groups){for(Store.Group g:groups)if(!g.coordinatesOnly())for(int at:PhotoAssignments.photoIndices(g))items.add(new Item(g,at));}
    /** Position of the photo whose family contains any of these view ids, or -1. */
    int indexOf(String group,Collection<String> views){for(int i=0;i<items.size();i++){Item item=items.get(i);if(item.group.id.equals(group)&&views.contains(item.photo()))return i;}return -1;}
    /** First photo not yet checked; the start of the list when every photo is checked or states are still loading. */
    int firstUnchecked(Map<String,Set<String>> checked){for(int i=0;i<items.size();i++){Set<String> done=checked.get(items.get(i).group.id);if(done!=null&&!done.contains(items.get(i).photo()))return i;}return 0;}
    int unchecked(Map<String,Set<String>> checked){int n=0;for(Item item:items){Set<String> done=checked.get(item.group.id);if(done!=null&&!done.contains(item.photo()))n++;}return n;}
}
