package com.sohail.pocketgallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView title;
    private ListView list;
    private final List<File> files = new ArrayList<>();
    private VaultManager vault;
    private String sessionPin;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        vault = new VaultManager(this);
        buildUi();
        home();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(14),dp(14),dp(14));
        title = new TextView(this);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1,-2));
        list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void home() {
        title.setText("Pocket Gallery");
        List<String> items = new ArrayList<>();
        Collections.addAll(items,
                "Recent Downloads","Photos","Videos","PDF files","Apps / APK files",
                "HTML files","All files","Private Vault (PIN)");
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items));
        list.setOnItemClickListener((p,v,pos,id)->{
            String[] cats={"recent","photos","videos","pdf","apk","html","all","vault"};
            if ("vault".equals(cats[pos])) openVault(); else openCategory(cats[pos]);
        });
        list.setOnItemLongClickListener(null);
    }

    private void openCategory(String category) {
        if (!hasStorageAccess()) {
            requestStorageAccess();
            Toast.makeText(this,"Storage access دیں، پھر category دوبارہ کھولیں۔",Toast.LENGTH_LONG).show();
            return;
        }
        title.setText(label(category));
        files.clear();
        File base = "recent".equals(category)
                ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                : Environment.getExternalStorageDirectory();
        walk(base, category, 0);
        files.sort((a,b)->Long.compare(b.lastModified(),a.lastModified()));
        renderFiles();
    }

    private void walk(File dir, String cat, int depth) {
        if (dir==null || !dir.exists() || depth>16) return;
        File[] children;
        try { children=dir.listFiles(); } catch(Exception e){ return; }
        if (children==null) return;
        for(File f:children){
            if(f.isDirectory()){
                String p=f.getAbsolutePath();
                if(p.contains("/Android/data/")||p.contains("/Android/obb/")) continue;
                walk(f,cat,depth+1);
            } else if(matches(f,cat)) files.add(f);
        }
    }

    private boolean matches(File f,String cat){
        if("all".equals(cat)||"recent".equals(cat)) return true;
        String e=ext(f);
        if("photos".equals(cat)) return one(e,"jpg","jpeg","png","webp","gif","bmp","heic","heif");
        if("videos".equals(cat)) return one(e,"mp4","mkv","webm","3gp","avi","mov","m4v");
        if("pdf".equals(cat)) return "pdf".equals(e);
        if("apk".equals(cat)) return one(e,"apk","xapk","apks");
        if("html".equals(cat)) return one(e,"html","htm");
        return true;
    }

    private void renderFiles(){
        List<String> rows=new ArrayList<>();
        DateFormat df=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
        for(File f:files) rows.add(icon(f)+"  "+f.getName()+"\n"+size(f.length())+" • "+df.format(new Date(f.lastModified())));
        if(rows.isEmpty()) rows.add("No files found");
        list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows));
        list.setOnItemClickListener((p,v,pos,id)->{ if(pos<files.size()) openFile(files.get(pos)); });
        list.setOnItemLongClickListener((p,v,pos,id)->{
            if(pos<files.size()) actions(files.get(pos));
            return true;
        });
    }

    private void actions(File f){
        String[] a={"Open","Share","Move to Private Vault","Delete"};
        new AlertDialog.Builder(this).setTitle(f.getName()).setItems(a,(d,w)->{
            if(w==0) openFile(f);
            else if(w==1) share(f);
            else if(w==2) withPin(pin->{
                new Thread(()->{
                    try{
                        vault.encryptIntoVault(f,pin);
                        boolean deleted=f.delete();
                        runOnUiThread(()->{
                            files.remove(f);
                            renderFiles();
                            Toast.makeText(this,deleted?"Moved to vault":"Encrypted, original could not be deleted",Toast.LENGTH_LONG).show();
                        });
                    }catch(Exception e){ runOnUiThread(()->Toast.makeText(this,"Vault error",Toast.LENGTH_LONG).show()); }
                }).start();
            });
            else new AlertDialog.Builder(this).setTitle("Delete file?").setMessage(f.getName())
                    .setNegativeButton("Cancel",null).setPositiveButton("Delete",(x,y)->{
                        boolean ok=f.delete();
                        if(ok){ files.remove(f); renderFiles(); }
                        Toast.makeText(this,ok?"Deleted":"Delete not allowed",Toast.LENGTH_LONG).show();
                    }).show();
        }).show();
    }

    private void openVault(){
        withPin(pin->{
            sessionPin=pin;
            title.setText("Private Vault");
            List<File> vf=vault.listVaultFiles();
            List<String> rows=new ArrayList<>();
            for(File f:vf) rows.add("🔒  "+vault.originalName(f)+"\n"+size(vault.originalSize(f)));
            if(rows.isEmpty()) rows.add("Vault is empty");
            list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows));
            list.setOnItemClickListener((p,v,pos,id)->{
                if(pos>=vf.size()) return;
                new Thread(()->{
                    try{
                        File out=vault.decryptToCache(vf.get(pos),sessionPin);
                        runOnUiThread(()->openFile(out));
                    }catch(Exception e){ runOnUiThread(()->Toast.makeText(this,"Could not open file",Toast.LENGTH_LONG).show()); }
                }).start();
            });
            list.setOnItemLongClickListener((p,v,pos,id)->{
                if(pos>=vf.size()) return true;
                File target=vf.get(pos);
                String[] opts={"Restore to Downloads","Delete from Vault"};
                new AlertDialog.Builder(this).setTitle(vault.originalName(target)).setItems(opts,(d,w)->{
                    if(w==0){
                        new Thread(()->{
                            try{
                                File out=vault.restoreToDirectory(target,sessionPin,Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
                                runOnUiThread(()->Toast.makeText(this,"Restored: "+out.getName(),Toast.LENGTH_LONG).show());
                            }catch(Exception e){ runOnUiThread(()->Toast.makeText(this,"Restore failed",Toast.LENGTH_LONG).show()); }
                        }).start();
                    }else{
                        target.delete();
                        openVault();
                    }
                }).show();
                return true;
            });
        });
    }

    private interface PinCb { void ok(String pin); }
    private void withPin(PinCb cb){
        EditText input=new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("PIN");
        if(!vault.hasPin()){
            new AlertDialog.Builder(this).setTitle("Create Vault PIN").setMessage("کم از کم 4 ہندسوں کا PIN رکھیں۔")
                    .setView(input).setNegativeButton("Cancel",null).setPositiveButton("Create",(d,w)->{
                        String pin=input.getText().toString();
                        if(pin.length()<4){ Toast.makeText(this,"PIN کم از کم 4 digits ہو",Toast.LENGTH_LONG).show(); return; }
                        if(vault.setPin(pin)) cb.ok(pin);
                    }).show();
        }else{
            new AlertDialog.Builder(this).setTitle("Enter Vault PIN").setView(input).setNegativeButton("Cancel",null)
                    .setPositiveButton("Unlock",(d,w)->{
                        String pin=input.getText().toString();
                        if(vault.verifyPin(pin)) cb.ok(pin); else Toast.makeText(this,"Wrong PIN",Toast.LENGTH_LONG).show();
                    }).show();
        }
    }

    private void openFile(File f){
        Uri u=LocalFileProvider.uriForFile(f);
        Intent i=new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u,mime(f));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{ startActivity(i); }catch(ActivityNotFoundException e){ Toast.makeText(this,"No app found to open this file",Toast.LENGTH_LONG).show(); }
    }

    private void share(File f){
        Intent i=new Intent(Intent.ACTION_SEND);
        i.setType(mime(f));
        i.putExtra(Intent.EXTRA_STREAM,LocalFileProvider.uriForFile(f));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i,"Share file"));
    }

    private boolean hasStorageAccess(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)== PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R){
            try{
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:"+getPackageName())));
            }catch(Exception e){
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }else{
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},3001);
        }
    }

    @Override public void onBackPressed(){
        if(!"Pocket Gallery".contentEquals(title.getText())) home(); else super.onBackPressed();
    }

    private String label(String c){
        switch(c){
            case "recent": return "Recent Downloads";
            case "photos": return "Photos";
            case "videos": return "Videos";
            case "pdf": return "PDF files";
            case "apk": return "Apps / APK files";
            case "html": return "HTML files";
            default: return "All files";
        }
    }

    private String ext(File f){
        String n=f.getName();
        int d=n.lastIndexOf('.');
        return d<0?"":n.substring(d+1).toLowerCase(Locale.US);
    }

    private boolean one(String v,String...x){ for(String s:x) if(s.equals(v)) return true; return false; }

    private String icon(File f){
        String e=ext(f);
        if(one(e,"jpg","jpeg","png","webp","gif","bmp","heic","heif")) return "🖼";
        if(one(e,"mp4","mkv","webm","3gp","avi","mov","m4v")) return "🎬";
        if("pdf".equals(e)) return "📕";
        if(one(e,"apk","xapk","apks")) return "📦";
        if(one(e,"html","htm")) return "🌐";
        return "📄";
    }

    private String mime(File f){
        String e=ext(f);
        String m=MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        if(m!=null) return m;
        if("apk".equals(e)) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    private String size(long b){
        if(b<1024) return b+" B";
        double k=b/1024.0;
        if(k<1024) return String.format(Locale.US,"%.1f KB",k);
        double m=k/1024.0;
        if(m<1024) return String.format(Locale.US,"%.1f MB",m);
        return String.format(Locale.US,"%.2f GB",m/1024.0);
    }

    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}
