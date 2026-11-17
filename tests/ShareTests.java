package dev.beam.classic;

import android.content.*;
import android.net.Uri;
import java.io.IOException;
import java.util.*;
import static dev.beam.classic.BeamTests.check;

public final class ShareTests {
    private static void reject(Intent intent,String why)throws Exception{
        try{SharedContent.read(intent);throw new AssertionError(why);}catch(IOException expected){check(true,why);}
    }
    public static void run()throws Exception{
        Uri photo=Uri.parse("content://gallery/photos/123"),pdf=Uri.parse("content://files/doc/456");
        check(!SharedContent.isShare(null),"null intent ignored");
        check(SharedContent.read(new Intent(Intent.ACTION_MAIN))==null,"launcher intent not a share");
        Intent single=new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM,photo);
        SharedContent result=SharedContent.read(single);
        check(result.files.size()==1&&result.files.get(0).equals(photo),"photo stream accepted");
        check("image/jpeg".equals(result.mime),"shared MIME retained");
        single.setClipData(new ClipData("photo",new String[]{"image/jpeg"},new ClipData.Item(photo)));
        check(SharedContent.read(single).files.size()==1,"EXTRA_STREAM/ClipData deduplicated");
        single.putExtra(Intent.EXTRA_TEXT,"A photo 🌌");
        check("A photo 🌌".equals(SharedContent.read(single).text),"photo caption preserved separately");
        ArrayList<Uri> items=new ArrayList<>(Arrays.asList(photo,pdf,photo));
        Intent multiple=new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,items);
        result=SharedContent.read(multiple);
        check(result.files.equals(Arrays.asList(photo,pdf)),"multiple files keep order and deduplicate");
        ClipData clips=new ClipData("files",new String[]{"*/*"},new ClipData.Item(pdf));clips.addItem(new ClipData.Item(photo));
        multiple.setClipData(clips);
        check(SharedContent.read(multiple).files.equals(Arrays.asList(photo,pdf)),"multiple extra/clip copies deduplicate");
        Intent fallback=new Intent(Intent.ACTION_SEND).setType("application/pdf").setClipData(new ClipData("file",new String[]{"application/pdf"},new ClipData.Item(pdf)));
        check(SharedContent.read(fallback).files.get(0).equals(pdf),"ClipData-only file supported");
        Intent text=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"https://example.com/a?x=1&y=2");
        check(SharedContent.read(text).text.equals("https://example.com/a?x=1&y=2"),"shared URL preserved exactly");
        check(SharedContent.read(text).files.isEmpty(),"text has no attachment");
        Intent clipText=new Intent(Intent.ACTION_SEND).setType("text/plain").setClipData(new ClipData("text",new String[]{"text/plain"},new ClipData.Item("Hello")));
        check(SharedContent.read(clipText).text.equals("Hello"),"ClipData-only plain text supported");
        reject(new Intent(Intent.ACTION_SEND).setType("image/jpeg"),"missing stream rejected");
        reject(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT,"  "),"empty text rejected");
        for(String uri:new String[]{"file:///data/private.txt","https://example.com/photo.jpg","content:///no-authority","android.resource://some.app/123"})
            reject(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,Uri.parse(uri)),"unsupported URI rejected: "+uri);
        reject(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,"not a URI"),"wrong parcel type rejected");
        reject(new Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM,"not a list"),"wrong list extra rejected");
        reject(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT,123),"wrong text extra rejected");
        String large=new String(new char[Wire.MAX_BYTES+1]).replace('\0','x');
        reject(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT,large),"oversized shared text rejected");
        reject(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,photo).putExtra(Intent.EXTRA_TEXT,large),"oversized caption not silently truncated");
        String boundary=new String(new char[Wire.MAX_BYTES]).replace('\0','x');
        check(SharedContent.read(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT,boundary)).text.length()==Wire.MAX_BYTES,"text boundary accepted");
        String unicode=new String(new char[Wire.MAX_BYTES/2]).replace('\0','界');
        reject(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT,unicode),"text bounded in UTF-8 bytes");
        items.clear();for(int i=0;i<SharedContent.MAX_ITEMS;i++)items.add(Uri.parse("content://files/"+i));
        Intent limit=new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,items);
        check(SharedContent.read(limit).files.size()==32,"32 choices supported");
        items.add(Uri.parse("content://files/33"));reject(limit,"too many files rejected instead of silently dropped");
        ClipData mixed=new ClipData("bad",new String[]{"*/*"},new ClipData.Item(photo));mixed.addItem(new ClipData.Item(Uri.parse("file:///bad")));
        reject(new Intent(Intent.ACTION_SEND_MULTIPLE).setClipData(mixed),"mixed unsafe URI list rejected");
        try{result.files.add(photo);throw new AssertionError("mutable share result");}catch(UnsupportedOperationException expected){check(true,"share selection immutable");}
    }
}
