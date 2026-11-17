package android.net;
import android.os.Parcelable;
public final class Uri implements Parcelable {
    private final String value;
    private Uri(String value){this.value=value;}
    public static Uri parse(String value){return new Uri(value);}
    public String getScheme(){int i=value.indexOf(':');return i<0?null:value.substring(0,i);}
    public String getAuthority(){int i=value.indexOf("://");if(i<0)return null;String rest=value.substring(i+3);int slash=rest.indexOf('/');return slash<0?rest:rest.substring(0,slash);}
    public String toString(){return value;}
    public int hashCode(){return value.hashCode();}
    public boolean equals(Object other){return other instanceof Uri&&value.equals(((Uri)other).value);}
}
