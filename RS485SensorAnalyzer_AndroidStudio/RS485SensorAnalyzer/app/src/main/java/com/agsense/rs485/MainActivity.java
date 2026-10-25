package com.agsense.rs485;

import android.app.*;
import android.os.*;
import android.content.*;
import android.hardware.usb.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.hoho.android.usbserial.driver.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String USB_PERMISSION = "com.agsense.rs485.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbSerialPort port;
    private UsbDeviceConnection connection;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean stop = false;
    private TextView log, status;
    private Spinner baudSpinner, paritySpinner, slaveSpinner, functionSpinner;
    private EditText registerInput, countInput, hexInput;
    private Button connectButton, scanButton, readButton, sendButton, stopButton;
    private final int[] baudRates = {9600,19200,4800,38400,115200,2400};
    private final String[] parities = {"None (8N1)","Even (8E1)","Odd (8O1)"};
    private UsbSerialDriver selectedDriver;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (USB_PERMISSION.equals(intent.getAction())) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openSelected();
                else append("USB permission denied");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                stop = true; closePort(); append("USB disconnected");
            }
        }
    };
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        usbManager = (UsbManager)getSystemService(USB_SERVICE);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(18,20,18,12);
        root.setBackgroundColor(Color.rgb(246,248,251)); setContentView(root);
        TextView title = new TextView(this); title.setText("RS485 Sensor Analyzer"); title.setTextSize(23); title.setTextColor(Color.rgb(25,43,70)); root.addView(title);
        status = new TextView(this); status.setText("Disconnected"); status.setTextColor(Color.DKGRAY); root.addView(status);
        TextView note = new TextView(this); note.setText("Read-only Modbus diagnostics. Sensor must be powered separately and safely."); note.setTextSize(12); root.addView(note);
        baudSpinner = spinner(root, "Baud rate", new String[]{"9600","19200","4800","38400","115200","2400"});
        paritySpinner = spinner(root, "Parity", parities);
        String[] ids = new String[247]; for(int i=0;i<247;i++) ids[i] = String.valueOf(i+1);
        slaveSpinner = spinner(root,"Slave ID",ids);
        functionSpinner = spinner(root,"Modbus function",new String[]{"03 Holding registers","04 Input registers"});
        LinearLayout regRow = new LinearLayout(this); regRow.setOrientation(LinearLayout.HORIZONTAL); root.addView(regRow);
        registerInput = field(regRow,"Start register (decimal)","0"); countInput = field(regRow,"Count (1-16)","3");
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); root.addView(buttons);
        connectButton = button(buttons,"Connect USB",()->connectUsb());
        readButton = button(buttons,"Read",()->readOnce());
        scanButton = button(buttons,"Scan",()->scan());
        LinearLayout custom = new LinearLayout(this); custom.setOrientation(LinearLayout.HORIZONTAL); root.addView(custom);
        hexInput = field(custom,"Manual HEX (read-only requests)","01 03 00 00 00 03 05 CB");
        sendButton = button(custom,"Send HEX",()->sendHex());
        stopButton = button(root,"Stop scan",()->{stop=true;append("Stop requested");});
        TextView logLabel = new TextView(this); logLabel.setText("Communication log (HEX)"); root.addView(logLabel);
        ScrollView scroll = new ScrollView(this); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        log = new TextView(this); log.setTextSize(12); log.setTextColor(Color.rgb(20,30,40)); log.setTextIsSelectable(true); log.setPadding(8,8,8,8); scroll.addView(log);
        IntentFilter f = new IntentFilter(); f.addAction(USB_PERMISSION); f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,f);
        append("Ready. Connect a USB-RS485 adapter.");
    }
    private Spinner spinner(LinearLayout root,String label,String[] options) {
        TextView text = new TextView(this); text.setText(label); root.addView(text);
        Spinner s = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options); s.setAdapter(adapter); root.addView(s); return s;
    }
    private EditText field(LinearLayout root,String hint,String initial) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setTextSize(12); e.setHint(hint); e.setText(initial); root.addView(e,new LinearLayout.LayoutParams(0,-2,1)); return e;
    }
    private Button button(LinearLayout root,String label,Runnable run) {
        Button b = new Button(this); b.setText(label); b.setTextSize(11); root.addView(b,new LinearLayout.LayoutParams(root.getOrientation()==LinearLayout.HORIZONTAL?0:-1,-2,root.getOrientation()==LinearLayout.HORIZONTAL?1:0)); b.setOnClickListener(v->run.run()); return b;
    }
    private void append(String s) {runOnUiThread(()->{ if(log!=null){log.append(s+"\n"); if(log.length()>28000) log.setText(log.getText().subSequence(log.length()-18000,log.length()));} });}
    private void connectUsb() {
        if(port!=null){stop=true;closePort();append("Disconnected");return;}
        List<UsbSerialDriver> drivers=UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if(drivers.isEmpty()){append("No supported USB serial adapter found. Check OTG and adapter chipset.");return;}
        selectedDriver=drivers.get(0);
        UsbDevice device=selectedDriver.getDevice();
        append(String.format(Locale.US,"USB VID=%04X PID=%04X",device.getVendorId(),device.getProductId()));
        if(!usbManager.hasPermission(device)){
            PendingIntent pi=PendingIntent.getBroadcast(this,0,new Intent(USB_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device,pi);
        } else openSelected();
    }
    private synchronized void openSelected() {
        try {
            if(selectedDriver==null)return;
            connection=usbManager.openDevice(selectedDriver.getDevice());
            if(connection==null){append("Cannot open USB device");return;}
            port=selectedDriver.getPorts().get(0);port.open(connection);
            configure(Integer.parseInt(baudSpinner.getSelectedItem().toString()),paritySpinner.getSelectedItemPosition());
            runOnUiThread(()->status.setText("Connected - "+selectedDriver.getDevice().getDeviceName()));
            append("USB serial port opened");
        }catch(Exception e){append("Open error: "+e.getMessage());closePort();}
    }
    private synchronized void configure(int baud,int parity)throws IOException {
        if(port==null)throw new IOException("USB not connected");
        int p=parity==1?UsbSerialPort.PARITY_EVEN:parity==2?UsbSerialPort.PARITY_ODD:UsbSerialPort.PARITY_NONE;
        port.setParameters(baud,8,UsbSerialPort.STOPBITS_1,p);
    }
    private void readOnce(){
        final int baud=Integer.parseInt(baudSpinner.getSelectedItem().toString()), parity=paritySpinner.getSelectedItemPosition();
        final int slave=slaveSpinner.getSelectedItemPosition()+1, fn=functionSpinner.getSelectedItemPosition()==0?3:4;
        final int reg,count;
        try {reg=Integer.parseInt(registerInput.getText().toString().trim());count=Integer.parseInt(countInput.getText().toString().trim());
            if(reg<0||reg>65535||count<1||count>16||reg+count>65536)throw new Exception();
        }catch(Exception e){append("Invalid register/count");return;}
        executor.submit(()->{try{configure(baud,parity); transact(modbusRequest(slave,fn,reg,count),true);}catch(Exception e){append("Read error: "+e.getMessage());}});
    }
    private void scan(){
        if(port==null){append("Connect USB first");return;}
        stop=false;
        // A bounded, read-only discovery sweep; 1 register at address 0, IDs 1..10.
        executor.submit(()->{
            append("Scan started: baud 9600/19200/4800/38400, parity N/E, IDs 1..10, functions 03/04, register 0");
            int hits=0;
            outer: for(int baud:new int[]{9600,19200,4800,38400})for(int parity:new int[]{0,1})for(int id=1;id<=10;id++)for(int fn:new int[]{3,4}) {
                if(stop)break outer;
                try{configure(baud,parity);byte[] req=modbusRequest(id,fn,0,1);byte[] resp=exchange(req,250);
                    if(validResponse(resp,id,fn)){
                        hits++;append("MATCH baud="+baud+" parity="+parity+" ID="+id+" fn="+fn+" RX="+hex(resp));
                    }
                }catch(Exception e){append("Scan stopped: "+e.getMessage());break outer;}
            }
            append("Scan finished. Valid responses: "+hits+(stop?" (stopped)":""));
        });
    }
    private void sendHex(){
        String s=hexInput.getText().toString().replaceAll("\\s+","");
        if(s.length()<2||s.length()%2!=0||!s.matches("(?i)[0-9a-f]+")){append("Invalid HEX");return;}
        byte[] req=new byte[s.length()/2];for(int i=0;i<req.length;i++)req[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        // Restrict custom frames to Modbus read-only requests with correct CRC.
        if(req.length!=8||((req[1]&255)!=3&&(req[1]&255)!=4)||!crcOk(req)||((req[0]&255)<1)||((req[0]&255)>247)){
            append("Blocked: manual mode only permits valid Modbus 03/04 read frames (8 bytes, valid CRC, ID 1..247).");return;
        }
        executor.submit(()->{try{transact(req,true);}catch(Exception e){append("HEX error: "+e.getMessage());}});
    }
    private void transact(byte[] request,boolean verbose)throws IOException {
        byte[] response=exchange(request,650);
        if(verbose){append("TX "+hex(request));append("RX "+(response.length==0?"(timeout)":hex(response)));
            if(response.length>0)append("CRC "+(crcOk(response)?"OK":"invalid")+" | "+(validResponse(response,request[0]&255,request[1]&255)?"Modbus response recognized":"not a matching Modbus response"));
            if(validResponse(response,request[0]&255,request[1]&255)&&response.length>5&&(response[1]&0x80)==0){
                StringBuilder values=new StringBuilder("Registers (unsigned decimal): ");for(int i=3;i+1<response.length-2;i+=2)values.append(((response[i]&255)<<8)|(response[i+1]&255)).append(' ');append(values.toString());
            }
        }
    }
    private synchronized byte[] exchange(byte[] req,int timeout)throws IOException {
        if(port==null)throw new IOException("USB disconnected");
        // Drain stale input before each request.
        byte[] tmp=new byte[256];try{while(port.read(tmp,20)>0){} }catch(IOException ignored){}
        port.write(req,1000);
        ByteArrayOutputStream buf=new ByteArrayOutputStream();long end=System.nanoTime()+timeout*1000000L;
        while(System.nanoTime()<end&&buf.size()<256&&!stop){
            int n=port.read(tmp,Math.min(80,Math.max(1,(int)((end-System.nanoTime())/1000000L))));
            if(n>0){buf.write(tmp,0,n);byte[] data=buf.toByteArray();if(data.length>=5&&crcOk(data))break;}
        }
        return buf.toByteArray();
    }
    private static byte[] modbusRequest(int id,int fn,int start,int count){
        byte[] b={(byte)id,(byte)fn,(byte)(start>>8),(byte)start,(byte)(count>>8),(byte)count,0,0};int crc=crc(b,6);b[6]=(byte)crc;b[7]=(byte)(crc>>8);return b;
    }
    private static int crc(byte[] b,int length){int crc=0xffff;for(int i=0;i<length;i++){crc^=b[i]&255;for(int j=0;j<8;j++)crc=(crc&1)!=0?(crc>>>1)^0xa001:crc>>>1;}return crc;}
    private static boolean crcOk(byte[] b){if(b.length<4)return false;int c=crc(b,b.length-2);return (b[b.length-2]&255)==(c&255)&&(b[b.length-1]&255)==((c>>8)&255);}
    private static boolean validResponse(byte[] b,int id,int fn){return b.length>=5&&(b[0]&255)==id&&((b[1]&255)==fn||(b[1]&255)==(fn|0x80))&&crcOk(b)&&(((b[1]&255)==(fn|0x80)&&b.length==5)||((b[1]&255)==fn&&b.length==5+(b[2]&255)));}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02X ",x&255));return s.toString().trim();}
    private synchronized void closePort(){try{if(port!=null)port.close();}catch(Exception ignored){}port=null;try{if(connection!=null)connection.close();}catch(Exception ignored){}connection=null;runOnUiThread(()->{if(status!=null)status.setText("Disconnected");});}
    @Override protected void onDestroy(){stop=true;closePort();executor.shutdownNow();unregisterReceiver(receiver);super.onDestroy();}
}
