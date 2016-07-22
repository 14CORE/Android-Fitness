/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package smu_bme.beats;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import smu_bme.beats.Chart.PathView;
import smu_bme.beats.Database.DbHelper;
import smu_bme.beats.Chart.ChartThread;
import smu_bme.beats.bluetooth.BluetoothService;
import smu_bme.beats.bluetooth.DeviceListActivity;


/**
 * This is the layout_content Activity that displays the current chat session.
 */
public class MainActivity extends AppCompatActivity implements OnClickListener {
    // Debugging
    private static final String TAG = "MainActivity";

    public static final int MESSAGE_STATE_CHANGE = 1, MESSAGE_READ = 2, MESSAGE_WRITE = 3, MESSAGE_DEVICE_NAME = 4, MESSAGE_TOAST = 5, SHOW_RESPONSE = 6;
    private static final int REQUEST_CONNECT_DEVICE = 2, REQUEST_ENABLE_BT = 3;
    public static final String DEVICE_NAME = "device_name", TOAST = "toast";

    // Intent request codes

    // Layout Views
    private Intent serverIntent;
    private FloatingActionButton fab;
    private Snackbar snackbar;
    private ViewGroup viewGroup;
    private ImageButton editMode, editDate;
    private TextView showMode, showDate, showPace, connectState, showBPM;
    private Button requestButton;
    private TextView requestText;
    ChartThread chartThread;

    private int mode2 = 0, year, month, day, basicSteps = 0, buttonState = 0;
    private String mode2_name, previousBPM = "0", previousSteps, mConnectedDeviceName = null;

    private Calendar calendar;
    private SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
    Pattern pattern;
    private PathView pathView;

    // Array adapter for the conversation thread
    // private ArrayAdapter<String> mConversationArrayAdapter;
    // String buffer for outgoing messages
    private StringBuffer mOutStringBuffer;
    // Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;
    // Member object for the chat services
    private BluetoothService mChatService = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(this);
        viewGroup = (ViewGroup) findViewById(R.id.content);
        showDate = (TextView) findViewById(R.id.show_date);
        editDate = (ImageButton) findViewById(R.id.edit_date);
        editDate.setOnClickListener(this);
        calendar = Calendar.getInstance();
        year = calendar.get(Calendar.YEAR);
        month = calendar.get(Calendar.MONTH);
        day = calendar.get(Calendar.DAY_OF_MONTH);
        showDate = (TextView) findViewById(R.id.show_date);
        showMode = (TextView) findViewById(R.id.show_mode);
        editMode = (ImageButton) findViewById(R.id.edit_mode);
        editMode.setOnClickListener(this);
        mode2_name = getString(R.string.mode11);
        connectState = (TextView) findViewById(R.id.connect_state);
        previousSteps = "0";
        requestButton = (Button) findViewById(R.id.send_request);
        requestButton.setOnClickListener(this);
        requestText = (TextView) findViewById(R.id.request_text);
//		TODO basicSteps init
        showPace = (TextView) findViewById(R.id.show_pace);
        showPace.setText(String.valueOf(basicSteps));
        String regex = "([S]\\d+)[S][C][L][L]|([P]\\d+)[P]|([B]\\d+)[B]|([Q]\\d+)[Q]";
        pattern = Pattern.compile(regex);
        showBPM = (TextView) findViewById(R.id.show_bpm);
        pathView = (PathView) findViewById(R.id.path_view);
//        lineView = (LineView) findViewById(R.id.line_view);


        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
        }

        /** Initializing LogChart */
        chartThread = new ChartThread();
//		BarChart weekChart = (BarChart) findViewById(R.id.weekChart);
//		LineChart dayChart = (LineChart) findViewById(R.id.dayChart);
//		LineChart instantChart = (LineChart) findViewById(R.id.instant_chart);

//		chartThread.init(getBaseContext(), weekChart, dayChart, null);
        try {
            chartThread.refreshLogChart(calendar, mode2);
        } catch (NullPointerException e) {
            Log.d(Thread.currentThread().getName(), "init fail for null pointer exception");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(
                    BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            // Otherwise, setup the chat session
        } else {
            if (mChatService == null)
                setupChat(calendar);
        }

    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity
        // returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't
            // started already
            if (mChatService.getState() == BluetoothService.STATE_NONE) {
                // Start the Bluetooth chat services
                mChatService.start();
            }
        }
    }


    private void setupChat(Calendar calendar) {

        // Initialize the array adapter for the conversation thread
//		mConversationArrayAdapter = new ArrayAdapter<String>(this,
//				R.layout.message);
//		mConversationView = (ListView) findViewById(R.id.in);
//		mConversationView.setAdapter(mConversationArrayAdapter);

        // Initialize the BluetoothService to perform bluetooth connections
        mChatService = new BluetoothService(this, mHandler);

        // Initialize the buffer for outgoing messages
//		mOutStringBuffer = new StringBuffer("");

        showDate.setText(format.format(calendar.getTime()));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop the Bluetooth chat services
        if (mChatService != null)
            mChatService.stop();
    }


    // The Handler that gets information back from the BluetoothService
    private final Handler mHandler = new Handler() {

        private String num;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothService.STATE_CONNECTED:
                            connectState.setText(getString(R.string.title_connected_to, mConnectedDeviceName));
                            break;
                        case BluetoothService.STATE_CONNECTING:
                            connectState.setText(R.string.title_connecting);
                            break;
                        case BluetoothService.STATE_LISTEN:
                        case BluetoothService.STATE_NONE:
                            buttonState = 1;
                            connectState.setText(getString(R.string.title_not_connected));
                            fab.setImageResource(R.drawable.ic_disconnect);
                            break;
                    }
                    break;
                case MESSAGE_READ:
                    buttonState = 2;
                    fab.setImageResource(R.drawable.ic_sync);
                    byte[] readBuf = (byte[]) msg.obj;
//				 TODO construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    break;
                case MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                    Toast.makeText(getApplicationContext(),
                            "Connected to " + mConnectedDeviceName,
                            Toast.LENGTH_SHORT).show();
                    break;
                case MESSAGE_TOAST:
                    Toast.makeText(getApplicationContext(),
                            msg.getData().getString(TOAST), Toast.LENGTH_SHORT)
                            .show();
                    break;
                case SHOW_RESPONSE:
                    String response = (String) msg.obj;
                    requestText.setText(response);
            }
        }
    };

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat(calendar);
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show();
                    finish();
                }
        }
    }

    private void connectDevice(Intent data) {
        // Get the device MAC address
        String address = data.getExtras().getString(
                DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        mChatService.connect(device);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.send_request:
                Log.d("DEBUGGING", "onClick");
                sendRequestWithHttpURLConnection();
                break;
            case R.id.fab:
                serverIntent = new Intent(MainActivity.this, DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
                setupChat(calendar);
                break;
            case R.id.edit_date:
                DatePickerDialog dialog = new DatePickerDialog(MainActivity.this, new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker view, int y, int m, int d) {
                        calendar.set(Calendar.YEAR, y);
                        calendar.set(Calendar.MONTH, m);
                        calendar.set(Calendar.DAY_OF_MONTH, d);
                        year = y;
                        month = m;
                        day = d;
                        setupChat(calendar);
                        chartThread.refreshLogChart(calendar, mode2);
                        calendar.set(Calendar.YEAR, y);
                        calendar.set(Calendar.MONTH, m);
                        calendar.set(Calendar.DAY_OF_MONTH, d);
                        year = y;
                        month = m;
                        day = d;
                        setupChat(calendar);
//						TODO
                    }
                }, year, month, day);
                dialog.show();
                break;
            case R.id.edit_mode:
                PopupMenu popupMenu = new PopupMenu(getApplicationContext(), v);
                popupMenu.inflate(R.menu.mode_select);
                popupMenu.getMenu().findItem(R.id.mode11).setChecked(true);
                popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        switch (item.getItemId()) {
                            case R.id.mode11:
                                mode2 = 0;
                                mode2_name = getString(R.string.mode11);
//						TODO CHANGE MODE
                                chartThread.refreshLogChart(calendar, mode2);
                                break;

                            case R.id.mode12:
                                mode2 = 1;
                                mode2_name = getString(R.string.mode12);
//						TODO CHANGE MODE
                                chartThread.refreshLogChart(calendar, mode2);
                                break;

                            case R.id.mode13:
                                mode2 = 2;
                                mode2_name = getString(R.string.mode13);
//						TODO CHANGE MODE
                                chartThread.refreshLogChart(calendar, mode2);
                                break;
                        }
                        showMode.setText(mode2_name);
                        return true;
                    }
                });
                popupMenu.show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
//			case R.id.theme_select:
//				new AlertDialog.Builder(MainActivity.this).setTitle(getString(R.string.theme_select))
//						.setSingleChoiceItems(arrayTheme, 0, new DialogInterface.OnClickListener() {
//							@Override
//							public void onClick(DialogInterface dialog, int which) {
//								themeId = which;
//							}
//						})
//						.setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {
//							@Override
//							public void onClick(DialogInterface dialog, int which) {
////								TODO themeId update
//								Toast.makeText(MainActivity.this, "ThemeId = " + themeId, Toast.LENGTH_SHORT).show();
//								onCreate(null);
//							}
//						})
//						.setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
//							@Override
//							public void onClick(DialogInterface dialog, int which) {
////								TODO themeId init
//							}
//						}).show();
//				break;
            case R.id.about:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void sendRequestWithHttpURLConnection(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    Log.d("Debugging", "Thread");
                    URL url = new URL("192.168.1.106:3000");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);
                    InputStream inputStream = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null){
                        response.append(line);
                    }
                    Message message = new Message();
                    message.what = SHOW_RESPONSE;
                    message.obj = response.toString();
                    mHandler.sendMessage(message);
                } catch (Exception e){
                    e.printStackTrace();
                } finally {
                    if (connection != null){
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }
}
