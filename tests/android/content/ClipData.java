package android.content;
import android.net.Uri;
import java.util.*;
public class ClipData {
    private final List<Item> items=new ArrayList<>();
    public ClipData(CharSequence label,String[] mimes,Item first){items.add(first);}
    public void addItem(Item item){items.add(item);}
    public int getItemCount(){return items.size();}
    public Item getItemAt(int i){return items.get(i);}
    public static class Item{
        private final Uri uri;private final CharSequence text;
        public Item(Uri value){uri=value;text=null;}
        public Item(CharSequence value){text=value;uri=null;}
        public Uri getUri(){return uri;}
        public CharSequence getText(){return text;}
    }
}
