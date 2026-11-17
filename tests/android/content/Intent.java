package android.content;
import android.os.Parcelable;
import java.util.*;
public class Intent {
    public static final String ACTION_SEND="android.intent.action.SEND",ACTION_SEND_MULTIPLE="android.intent.action.SEND_MULTIPLE",ACTION_MAIN="android.intent.action.MAIN";
    public static final String EXTRA_STREAM="android.intent.extra.STREAM",EXTRA_TEXT="android.intent.extra.TEXT";
    private String action,type;private ClipData clips;private final Map<String,Object> extras=new HashMap<>();
    public Intent(String action){this.action=action;}
    public String getAction(){return action;}
    public String getType(){return type;}
    public Intent setType(String type){this.type=type;return this;}
    public Intent putExtra(String key,Object value){extras.put(key,value);return this;}
    public Intent putParcelableArrayListExtra(String key,ArrayList<? extends Parcelable> values){extras.put(key,values);return this;}
    @SuppressWarnings("unchecked") public <T extends Parcelable>T getParcelableExtra(String key){return (T)extras.get(key);}
    @SuppressWarnings("unchecked") public <T extends Parcelable>ArrayList<T> getParcelableArrayListExtra(String key){return (ArrayList<T>)extras.get(key);}
    public CharSequence getCharSequenceExtra(String key){return (CharSequence)extras.get(key);}
    public Intent setClipData(ClipData value){clips=value;return this;}
    public ClipData getClipData(){return clips;}
}
