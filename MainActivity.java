package com.agsense.rs485explorer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.hardware.usb.*;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import com.hoho.android.usbserial.driver.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION="com.agsense.rs485explorer.USB_PERMISSION";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private UsbSerialPort port;
    private volatile boolean monitoring=false;
    private volatile boolean busy=false;
    private TextView status,log;
    private Spinner baud, parity;
    private EditText hex;
    private final StringBuilder history=new StringBuilder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SimpleDateFormat stamp=new SimpleDateFormat("HH:mm:ss.SSS",Locale.US);
    private final BroadcastReceiver permissionReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(!ACTION_USB_PERMISSION.equals(i.getAction()))return;
        UsbDevice device=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)&&device!=null)openDevice(device);
        else append("USB permission denied");
    }};
    @Override public void onCreate(Bundle b){super.onCreate(b);registerReceiver(permissionReceiver,new android.content.IntentFilter(ACTION_USB_PERMISSION));buildUi();append("Ready. Connect USB-RS485 adapter. Read-only diagnostics; power sensor separately.");}
    private TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(15);t.setPadding(0,14,0,4);return t;}
    private Button button(String s,LinearLayout box){Button b=new Button(this);b.setText(s);box.addView(b);return b;}
    private void buildUi(){ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(20,18,20,24);scroll.addView(root);setContentView(scroll);
        TextView title=label("RS485 Protocol Explorer");title.setTextSize(26);root.addView(title);
        status=label("Disconnected");root.addView(status);
        root.addView(label("Baud rate"));baud=new Spinner(this);String[] speeds={"9600","4800","19200","38400","57600","115200","2400","1200"};baud.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,speeds));root.addView(baud);
        root.addView(label("Parity"));parity=new Spinner(this);parity.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"None (8N1)","Even (8E1)","Odd (8O1)"}));root.addView(parity);
        Button connect=button("CONNECT USB",root);connect.setOnClickListener(v->connect());
        Button monitor=button("START RAW MONITOR",root);monitor.setOnClickListener(v->{if(monitoring){stopMonitor();monitor.setText("START RAW MONITOR");}else{startMonitor();monitor.setText("STOP RAW MONITOR");}});
        root.addView(label("HEX command (spaces optional, e.g. 01 03 00 00 00 03 05 CB)"));hex=new EditText(this);hex.setSingleLine(true);hex.setText("01 03 00 00 00 03 05 CB");root.addView(hex);
        Button send=button("SEND HEX + LISTEN",root);send.setOnClickListener(v->sendHex());
        Button apply=button("APPLY SERIAL SETTINGS",root);apply.setOnClickListener(v->applySettings());
        Button save=button("SHARE LOG",root);save.setOnClickListener(v->shareLog());
        Button clear=button("CLEAR LOG",root);clear.setOnClickListener(v->{synchronized(history){history.setLength(0);}log.setText("");});
        root.addView(label("RAW communication log (HEX and ASCII)"));log=label("");log.setTypeface(Typeface.MONOSPACE);log.setTextSize(12);log.setTextIsSelectable(true);root.addView(log);
        root.addView(label("Tip: RAW monitor only sees unsolicited data. Many RS485 sensors remain silent until polled by a specific command. Never send random write commands."));
    }
    private void append(String msg){String line; synchronized(stamp){line=stamp.format(new Date())+"  "+msg+"\n";}synchronized(history){history.append(line);if(history.length()>90000)history.delete(0,30000);}main.post(()->log.setText(history.toString()));}
    private int speed(){return Integer.parseInt(baud.getSelectedItem().toString());}
    private int parityValue(){int i=parity.getSelectedItemPosition();return i==1?UsbSerialPort.PARITY_EVEN:i==2?UsbSerialPort.PARITY_ODD:UsbSerialPort.PARITY_NONE;}
    private void connect(){UsbManager mgr=(UsbManager)getSystemService(USB_SERVICE);List<UsbSerialDriver> drivers=UsbSerialProber.getDefaultProber().findAllDrivers(mgr);if(drivers.isEmpty()){append("No supported USB serial adapter found");return;}UsbDevice device=drivers.get(0).getDevice();append(String.format(Locale.US,"USB VID=%04X PID=%04X",device.getVendorId(),device.getProductId()));if(!mgr.hasPermission(device)){PendingIntent pi=PendingIntent.getBroadcast(this,0,new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE);mgr.requestPermission(device,pi);}else openDevice(device);}
    private void openDevice(UsbDevice device){if(busy){append("Stop current operation first");return;}stopMonitor();worker.execute(()->{try{closePort();UsbManager mgr=(UsbManager)getSystemService(USB_SERVICE);UsbSerialDriver drv=null;for(UsbSerialDriver d:UsbSerialProber.getDefaultProber().findAllDrivers(mgr))if(d.getDevice().equals(device))drv=d;if(drv==null)throw new IOException("USB driver unavailable");UsbDeviceConnection c=mgr.openDevice(device);if(c==null)throw new IOException("USB open failed");port=drv.getPorts().get(0);port.open(c);port.setParameters(speed(),8,UsbSerialPort.STOPBITS_1,parityValue());main.post(()->status.setText("Connected: "+device.getDeviceName()));append("Serial opened at "+speed()+" baud");}catch(Exception e){append("Open error: "+e.getMessage());main.post(()->status.setText("Disconnected"));}});}
    private void applySettings(){if(monitoring||busy){append("Stop monitor / operation first");return;}worker.execute(()->{try{if(port==null)throw new IOException("Not connected");port.setParameters(speed(),8,UsbSerialPort.STOPBITS_1,parityValue());append("Settings applied: "+speed()+" baud, parity "+parity.getSelectedItem());}catch(Exception e){append("Settings error: "+e.getMessage());}});}
    private void startMonitor(){if(port==null){append("Connect USB first");return;}if(busy){append("Another operation is active");return;}monitoring=true;append("RAW RX monitor started");worker.execute(()->{byte[] b=new byte[4096];while(monitoring){try{int n=port.read(b,250);if(n>0)append("RX "+format(b,n));}catch(Exception e){if(monitoring)append("RX error: "+e.getMessage());break;}}monitoring=false;append("RAW monitor stopped");});}
    private void stopMonitor(){monitoring=false;}
    private void sendHex(){if(port==null){append("Connect USB first");return;}if(monitoring||busy){append("Stop monitor / operation first");return;}final byte[] command;try{String s=hex.getText().toString().replaceAll("\\s+","");if(s.length()==0||s.length()%2!=0||!s.matches("[0-9a-fA-F]+"))throw new IllegalArgumentException("Invalid HEX");command=new byte[s.length()/2];for(int i=0;i<command.length;i++)command[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);}catch(Exception e){append("HEX error: "+e.getMessage());return;}busy=true;worker.execute(()->{try{byte[] drain=new byte[1024];while(port.read(drain,30)>0){}port.write(command,1000);append("TX "+toHex(command,command.length));long end=SystemClock.elapsedRealtime()+1600;byte[] rx=new byte[4096];boolean received=false;while(SystemClock.elapsedRealtime()<end){int n=port.read(rx,200);if(n>0){append("RX "+format(rx,n));received=true;}}if(!received)append("RX timeout (no bytes)");}catch(Exception e){append("Communication error: "+e.getMessage());}finally{busy=false;}});}
    private static String toHex(byte[] b,int n){StringBuilder s=new StringBuilder();for(int i=0;i<n;i++)s.append(String.format(Locale.US,"%02X ",b[i]&255));return s.toString().trim();}
    private static String format(byte[] b,int n){StringBuilder a=new StringBuilder();for(int i=0;i<n;i++){int c=b[i]&255;a.append(c>=32&&c<=126?(char)c:'.');}return toHex(b,n)+" |ASCII| "+a;}
    private void shareLog(){String value; synchronized(history){value=history.toString();}Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,value);startActivity(Intent.createChooser(i,"Share RS485 log"));}
    private void closePort(){if(port!=null){try{port.close();}catch(Exception ignored){}port=null;}}
    @Override protected void onDestroy(){stopMonitor();try{unregisterReceiver(permissionReceiver);}catch(Exception ignored){}worker.shutdownNow();closePort();super.onDestroy();}
}
