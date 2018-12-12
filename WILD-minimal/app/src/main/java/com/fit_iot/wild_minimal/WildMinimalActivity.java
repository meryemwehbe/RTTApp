/*
WILD Minimal - WiFi RTT proof-of-concept for Android 9 Pie
Revision: 0.1 24-Aug-2018
Designed for ranging to Compulab WILD FTM Responders and tested with Google Pixel
"WILD" stands for WiFi Indoor Location Device
(C) Compulab 2018 https://www.fit-iot.com
Written by Irad Stavi irad@compulab.co.il
Special thanks to Prof. Berthold K.P. Horn of MIT CSAIL http://people.csail.mit.edu/bkph/ for his insightful advice

What the program does:
1. Get permissions
2. WiFi Scan to detect APs
3. Every 2 seconds send a ranging request to APs found in scan that are FTM responders and display the ranging results of each in a table

The program is written to be as simple as possible - a self contained activity with nearly sequential execution
Below is the flow in a nutshell:
onCreate: Requests permissions and starts 'repeat' which is called every 2 seconds
repeat: runs a simple state machine: permission_is_granted --(do scan)--> wifi_scan_is_requested --(scan-results received)--> wifi_scan_is_done
    then repeat starts calling range() every 2 seconds
range: build a 'ranging request' and send. onRangingResults will be called asynchronously when results are ready.
onRangingResults: Display the ranging results in a table (MAC address, measured range, standard deviation, signal strength)
 */

package com.fit_iot.wild_minimal;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.app.Application;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;

import java.io.File;
import java.io.FileWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class WildMinimalActivity extends AppCompatActivity {

    // variables

    Boolean permission_is_granted = false; // state variable indicating permission granted
    Boolean wifi_scan_is_requested = false; // state variable indicating wifi scan requested
    Boolean wifi_scan_is_done = false; // state variable indicating wifi scan is complete (a condition for ranging)
    TextView txtview; // the text view in which status and results are displayed
    EditText labelview;
    Button record_button, stop_button;
    EditText edittext;
    Handler repeat_handler; // a handler for calling 'repeat' repeatedly
    repeatclass repeat; // repeat is a runnable executed repeatedly
    WifiManager wifi_manager; // WiFi manager instance for WiFi scanning
    ScanWifiNetworkReceiver scan_receiver; // An instance for WiFi scanning callback
    WifiRttManager rtt_manager; // RTT manager instance for ranging
    List<ScanResult> wifi_aps; // list of available WiFi access points (some of them are FTM capable)
    int recorded_measurements;
    LocationManager mLocationManager;
    double lat;
    double lon;
    String writestring = "";
    ArrayList<ScanResult> access_points;
    ArrayList<String> access_points_macs;
    int n_aps = 4;

    String server_url = "http://192.168.1.175:8081/dbconfig.php";
    final String url = "http://192.168.1.175:8081/trilaterationWeb.py";
    AlertDialog.Builder builder;

    public WildMinimalActivity() throws MalformedURLException {
    }

    // member functions and classes

    @Override
    // execution starts here
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // respect your elders
        setContentView(R.layout.activity_wild_minimal); // initialize GUI
        txtview = (TextView) findViewById(R.id.range_textview); // associate text view in GUI
        record_button = (Button) findViewById(R.id.record_btn);
        stop_button = (Button) findViewById(R.id.stop_btn);
        labelview = (EditText) findViewById(R.id.label);
        builder = new AlertDialog.Builder(WildMinimalActivity.this);
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 1); // request permissions
        rtt_manager = (WifiRttManager) getApplicationContext().getSystemService(Context.WIFI_RTT_RANGING_SERVICE); // initialize RTT manager

        access_points = new ArrayList<>();
        access_points_macs = new ArrayList<>();

        record_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                recorded_measurements = 0;
                repeat_handler = new Handler(); // initialize repeat-handler
                repeat = new repeatclass(); // initialize repeat instance
                repeat_handler.post(repeat); // first call to repeat

            }
        });
        stop_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //writeFileOnInternalStorage(getApplicationContext(),labelview.getText().toString(),writestring);
                repeat_handler.removeCallbacksAndMessages(null); // first call to repeat


            }
        });


    }

    // permissions grant verdict
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        for (int i = 0; i < grantResults.length; i++)
            if (grantResults[i] != PERMISSION_GRANTED)
                return; // one of the permissions failed: abort
        permission_is_granted = true; // all requested permissions granted
    }

    private class repeatclass implements Runnable { // Runnable can be called repeatedly without blocking
        @Override
        public void run() {
            if ((permission_is_granted) && (!wifi_scan_is_requested)) { // permission granted is a condition for scanning and scanning is done only once
               // wifi_scan_is_requested = true; // don't call again
                txtview.setText("Scanning..."); // display what happens now
                scan(); // to start WiFi scanning
            }
            if (wifi_scan_is_done) // indicates all initializtion done, execute repeatedly the below
                range(); // do WiFi RTT ranging
            repeat_handler.postDelayed(repeat, 1000); // call repeat->run again in 2 seconds
        }

    }

    // request scan of WiFi APs
    public void scan() {
        wifi_manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE); // initialize wifi manager
        if (!wifi_manager.isWifiEnabled()) { // check if wifi enabled
            txtview.setText("WiFi is disabled. Please enable and run app again."); // for simplicity we don't retry here
            return; // can't scan with disabled WiFi
        }
        IntentFilter filter = new IntentFilter(wifi_manager.SCAN_RESULTS_AVAILABLE_ACTION); // this is...
        scan_receiver = new ScanWifiNetworkReceiver(); // ...how we...
        getApplicationContext().registerReceiver(scan_receiver, filter); //...register the scan callback
        wifi_manager.startScan(); // startScan is deprecated, but as of Android Pie - it must be used!
        return; // scan initiated
    }

    // called when WiFi scan is complete
    private class ScanWifiNetworkReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            wifi_aps = wifi_manager.getScanResults(); // get list of WiFi access points found. Only some of them are FTM capable
            for (int i = 0; i < wifi_aps.size(); i++) { // examine the APs from the WiFi scan...
                if (wifi_aps.get(i).is80211mcResponder()) { // ...if the AP is a responder...
                    if (!access_points_macs.contains(wifi_aps.get(i).BSSID)) {
                        access_points.add(wifi_aps.get(i));
                        access_points_macs.add(wifi_aps.get(i).BSSID);
                    }// ...add it to the list...
                }
            }
            if(access_points.size() == n_aps)
            {
                wifi_scan_is_done = true;
                wifi_scan_is_requested = true;
            }// update state variable
        }
    }

    // Request FTM ranging
    public void range() {
        RangingRequest ranging_request; // mandatory container with responders data - an argument when issuing ranging request
        RangingRequest.Builder builder; // used for building the the ranging_request
        int number_of_ftmrs = 0; // ensure there are any ftmrs - otherwise you get an exception in startRanging

        builder = new RangingRequest.Builder();

        for (int i = 0; i < wifi_aps.size(); i++) { // examine the APs from the WiFi scan...
            if (wifi_aps.get(i).is80211mcResponder()) { // ...if the AP is a responder...
                builder.addAccessPoint(wifi_aps.get(i)); // ...add it to the list...
                number_of_ftmrs++; // ...and indicate the list of responders is not empty
            }
        }

        if (number_of_ftmrs > 0) { // if the ftmrs list is not empty
            ranging_request = builder.build(); // prepare the ranging request structure...
            if (checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED) // ...ensure user permitted ranging...
                rtt_manager.startRanging(ranging_request, AsyncTask.SERIAL_EXECUTOR, rangingcallback); //...and send out the ranging request. When results are obtained, rangingcallback will be called

            else // user did not permit ranging
                txtview.setText("No fine-location permission"); // ...display this fact and do not try ranging
        } else { // no ftmrs found...
            txtview.setText("No FTMRs found"); // ...display this fact and do not try ranging
        }
    }

    private int SendOnePoint( final long time,final int range,final int rssi,final int std,final int attemp,final int success ) {
        StringRequest stringRequest = new StringRequest(Request.Method.POST, server_url, new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {

                //builder.setTitle("Server Response");
                //builder.setMessage("Response :"+response);
                //builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                  //@Override
                 //public void onClick(DialogInterface dialog, int which) {

                //}
                //});
                //AlertDialog alertDialog = builder.create();
                //alertDialog.show();

            }
        }

                , new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Toast.makeText(WildMinimalActivity.this, error.getMessage(), Toast.LENGTH_SHORT).show();
                error.printStackTrace();

            }
        }) {
            @Override
            protected Map<String, String> getParams() throws AuthFailureError {
                Map<String, String> Params = new HashMap<String, String>();

                Params.put("timestamp",Long.toString(time));
                Params.put("range",Integer.toString(range));
                Params.put("rssi",Integer.toString(rssi));
                Params.put("std",Integer.toString(std));
                Params.put("numattemp",Integer.toString(attemp));
                Params.put("numsuccess",Integer.toString(success));

                return Params;

            }
        };
        Mysingleton.getInstance(WildMinimalActivity.this).addTorequestque(stringRequest);
        recorded_measurements++;

        return 0;

        // This is where you do your work in the UI thread.
        // Your worker tells you in the message what to do.
    }

    private int LargeSend(final List macs, final List ranges ,final List rssi ) {
        StringRequest stringRequest = new StringRequest(Request.Method.POST, server_url, new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {

                //builder.setTitle("Server Response");
                //builder.setMessage("Response :"+response);
                //builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                 // @Override
                 //public void onClick(DialogInterface dialog, int which) {

                //}
                //});
                //AlertDialog alertDialog = builder.create();
                //alertDialog.show();

            }
        }

                , new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Toast.makeText(WildMinimalActivity.this, error.getMessage(), Toast.LENGTH_SHORT).show();
                error.printStackTrace();

            }
        }) {
            @Override
            protected Map<String, String> getParams() throws AuthFailureError {
                Map<String, String> Params = new HashMap<String, String>();
                for (int i=0 ; i < macs.size(); i++){
                    Params.put(macs.get(i).toString(), ranges.get(i).toString());
                    Params.put("r_" + macs.get(i).toString(), rssi.get(i).toString());
                }
                int time = (int) (System.currentTimeMillis());
                Timestamp tsTemp = new Timestamp(time);
                String ts =  tsTemp.toString();
                Params.put("time",ts);
                return Params;

            }
        };
        Mysingleton.getInstance(WildMinimalActivity.this).addTorequestque(stringRequest);
        recorded_measurements++;

        return 0;

        // This is where you do your work in the UI thread.
        // Your worker tells you in the message what to do.
    }

    private int PreparetoSend(final List macs, final List ranges ) {
        StringRequest stringRequest = new StringRequest(Request.Method.POST, server_url, new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {

                //builder.setTitle("Server Response");
                //builder.setMessage("Response :"+response);
                //builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                //  @Override
                // public void onClick(DialogInterface dialog, int which) {

                //}
                //});
                //AlertDialog alertDialog = builder.create();
                //alertDialog.show();

            }
        }

                , new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Toast.makeText(WildMinimalActivity.this, error.getMessage(), Toast.LENGTH_SHORT).show();
                error.printStackTrace();

            }
        }) {
            @Override
            protected Map<String, String> getParams() throws AuthFailureError {
                Map<String, String> Params = new HashMap<String, String>();
                for (int i=0 ; i < macs.size(); i++){
                    Params.put(macs.get(i).toString(), ranges.get(i).toString());
                }
                int time = (int) (System.currentTimeMillis());
                Timestamp tsTemp = new Timestamp(time);
                String ts =  tsTemp.toString();
                Params.put("time",ts);
                return Params;

            }
        };
        Mysingleton.getInstance(WildMinimalActivity.this).addTorequestque(stringRequest);
        recorded_measurements++;

        return 0;

        // This is where you do your work in the UI thread.
        // Your worker tells you in the message what to do.
    }

    public void writeFileOnInternalStorage(Context mcoContext,String sFileName, String sBody){
        File file = new File("/storage/emulated/0","mydir");
        if(!file.exists()){
            file.mkdir();
        }

        try{
            File gpxfile = new File(file, sFileName);
            FileWriter writer = new FileWriter(gpxfile);
            writer.append(sBody);
            writer.flush();
            writer.close();

        }catch (Exception e){
            e.printStackTrace();

        }
    }

    // ranging results obtained. Check and display them
    private RangingResultCallback rangingcallback = new RangingResultCallback() {
        @Override
        public void onRangingFailure(final int i) { // ranging failed completely
            txtview.setText(String.format("Ranging failed with code %d", i)); // display the error
        } // nothing to say about failed ranging

        @Override
        public void onRangingResults(List<RangingResult> results) { // ranging succeeded - display the info obtained in a table

            String s = ("|       MAC       |Range |StdDev| RSSI  |\n"); // captions
            final List macs = new ArrayList(); // String to save macs
            final List ranges = new ArrayList(); // String to save macs
            final List rssi = new ArrayList();
            final List contents = new ArrayList();
            final List numattemp = new ArrayList();
            final List numsuccess = new ArrayList();
            HTTPPostActivity httpactivity = new HTTPPostActivity();

            for (int i = 0; i < results.size(); i++) { // loop through results and collect data into the string s
               //contents.add(results.get(i).describeContents());
               //numattemp.add(results.get(i).getNumAttemptedMeasurements());
               //numsuccess.add(results.get(i).getNumSuccessfulMeasurements());
               //long time = results.get(i).getRangingTimestampMillis();

                s += ("|" + results.get(i).getMacAddress().toString() + "|"); // show MAC of ftmr

                if (results.get(i).getStatus() == RangingResult.STATUS_SUCCESS) { // is status for the ftmr range good?

                    s += String.format("%5.2f", results.get(i).getDistanceMm() / 1000.0) + "m|" + // obtained range - convert from mm to m and format
                            String.format("%5.2f", results.get(i).getDistanceStdDevMm() / 1000.0) + "m|" + // obtained standard deviation  - convert from mm to m and format
                            String.format("%4d", results.get(i).getRssi()) + "dBm|" + // received signal strength indicator in dBm
                            "\n";

                    writestring += results.get(i).getMacAddress().toString()+ '|' + String.format("%5.2f", results.get(i).getDistanceMm() / 1000.0) + "m|" + // obtained range - convert from mm to m and format
                            String.format("%5.2f", results.get(i).getDistanceStdDevMm() / 1000.0) + "m|" + // obtained standard deviation  - convert from mm to m and format
                            String.format("%4d", results.get(i).getRssi()) + "dBm|" + // received signal strength indicator in dBm
                            "\n";
                    macs.add(results.get(i).getMacAddress().toString());
                    ranges.add(String.format("%5.2f", results.get(i).getDistanceMm() / 1000.0));
                    rssi.add(Integer.toString(results.get(i).getRssi()));
                    // , Integer.toString(results.get(i).getRssi()), Integer.toString(results.get(i).getDistanceStdDevMm()));

                    //SendOnePoint(results.get(i).getRangingTimestampMillis(),results.get(i).getDistanceMm(),
                            //results.get(i).getRssi(),results.get(i).getDistanceStdDevMm(),results.get(i).getNumAttemptedMeasurements(),
                            //results.get(i).getNumSuccessfulMeasurements());

                } else
                    s += "ranging failed\n"; // no data to show. Display that fact


            }
            //PreparetoSend(macs,ranges);
            LargeSend(macs,ranges,rssi);
            //httpactivity.startSendHttpRequestThread(url,macs,ranges,lat,lon);
            txtview.setText (s); // send string to display

        }
    };
}



