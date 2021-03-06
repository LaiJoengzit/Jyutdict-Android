package cc.ecisr.jyutdict;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;

import com.github.zackratos.ultimatebar.UltimateBar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import cc.ecisr.jyutdict.struct.HeaderInfo;
import cc.ecisr.jyutdict.utils.StringUtil;
import cc.ecisr.jyutdict.utils.EnumConst;
import cc.ecisr.jyutdict.utils.HttpUtil;
import cc.ecisr.jyutdict.utils.ToastUtil;

/**
 * app 的主頁面，包含一個查詢結果的 fragment
 */
public class MainActivity extends AppCompatActivity {
	private static final String TAG = "`MainActivity";
	private static final String URL_API_ROOT = "https://www.jyutdict.org/api/v0.9/";
	
	EditText inputEditText;
	Button btnQueryConfirm;
	Spinner spinnerQueryLocation;
	Switch switchQueryOpts1, switchQueryOpts2, switchQueryOpts3;
	ResultFragment resultFragment;
	ProgressBar loadingProgressBar;
	Toolbar toolbar;
	LinearLayout lyMain, lyAdvancedSearch;
	
	// 在輸入框輸入的字符串，在按下查詢按鈕時更新
	String inputString;
	
	// 查詢按鈕字體的顏色，僅用於功能測試
	int previousColor;
	
	// 是否已成功獲取到泛粵字表表頭並且完成初始化步驟
	boolean isPrepared = false;
	
	// 下拉選擇框的 Adapter，存放的是可供查詢的查詢地名
	ArrayAdapter<String> locationsAdapter;
	
	// 儲存泛粵字表的表頭
	// 內部以静态方式儲存
	HeaderInfo headerInfo;
	
	// 用於獲取用戶的設置，與存儲各開關的狀態
	SharedPreferences sp;
	
	// 用於網絡線程與主線程間的通信
	Handler mainHandler;
	// 用於向服務器發送請求，與接收回應
	HttpUtil query = new HttpUtil(HttpUtil.GET);
	
	// 指示蒐索模式，查通用表字/查通用表音/查泛粵表
	// 在按下查詢按鈕時更新
	// 並根據這個狀態來解析JSON
	int queryObjectWhat = EnumConst.QUERYING_CHARA;
	
	/**
	 * 初始化界面，獲取界面上各物件的視圖
	 */
	void getView() {
		lyMain = findViewById(R.id.whole_main_layout);
		inputEditText = findViewById(R.id.edit_text_input);
		btnQueryConfirm = findViewById(R.id.btn_query);
		spinnerQueryLocation = findViewById(R.id.locate_spinner);
		lyAdvancedSearch = findViewById(R.id.input_advanced_switch);
		switchQueryOpts1 = findViewById(R.id.switch_select_sheet);
		switchQueryOpts2 = findViewById(R.id.switch_reverse_search);
		switchQueryOpts3 = findViewById(R.id.switch_use_regex);
		loadingProgressBar = findViewById(R.id.loading_progress);
		toolbar = findViewById(R.id.tool_bar);
		
		setSupportActionBar(toolbar);
		locationsAdapter = new ArrayAdapter<>(this, R.layout.spinner_drop_down_item);
		spinnerQueryLocation.setAdapter(locationsAdapter);
		locationsAdapter.add("字/音");
		locationsAdapter.add("俗字");
	}
	
	@SuppressLint("HandlerLeak")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		UltimateBar.Companion.with(this)
				.statusDark(true)           // 状态栏灰色模式(Android 6.0+)
				.applyNavigation(true)      // 应用到导航栏
				.navigationDark(false)      // 不导航栏灰色模式(Android 8.0+)
				.create().immersionBar();
		getView();
		if (savedInstanceState == null) {
			resultFragment = new ResultFragment();
			getSupportFragmentManager().beginTransaction().add(R.id.result_fragment, resultFragment).commit();
		}
		initPermission();
		
		mainHandler = new Handler() {
			@Override
			public void handleMessage(@NonNull Message msg) {
				switch (msg.what) {
					case EnumConst.INITIALIZE_LOCATIONS: // 初始化表頭
						try {
							JSONArray headerArray = new JSONObject( // TODO 改用其它第三方JSON來解析
									msg.obj.toString()
							).getJSONArray("__valid_options");
							headerInfo = new HeaderInfo(headerArray);
							
							locationsAdapter.addAll(HeaderInfo.getCityList());
							
							spinnerQueryLocation.setSelection(
									sp.getInt("spinner_selected_position", 0));
							isPrepared = true;
							if (inputEditText.getText().length() != 0) search();
						} catch (JSONException ignored) {}
						break;
					case HttpUtil.REQUEST_CONTENT_SUCCESSFULLY:
						resultFragment.parseJson(msg.obj.toString(), queryObjectWhat); // 解析json
						loadingProgressBar.setVisibility(View.GONE);
						break;
					case HttpUtil.REQUEST_CONTENT_FAIL:
						ToastUtil.msg(MainActivity.this, getString(R.string.error_tips_network, msg.obj.toString()));
						loadingProgressBar.setVisibility(View.GONE);
						break;
					default:
						break;
				}
				btnQueryConfirm.setEnabled(true);
			}
		};
		
		// 查詢按鈕
		btnQueryConfirm.setOnClickListener(v -> search());
		
		// 監聽焦點在輸入框內的軟鍵盤的確認按鈕
		inputEditText.setOnEditorActionListener((v, actionId, event) -> {
			Log.i(TAG, "onEditorAction: " + actionId);
			if(actionId == EditorInfo.IME_ACTION_SEARCH){
				search();
				try { // 關閉軟鍵盤
					((InputMethodManager) MainActivity.this.getSystemService(Context.INPUT_METHOD_SERVICE))
							.hideSoftInputFromWindow(MainActivity.this.getCurrentFocus().getWindowToken(),
									InputMethodManager.HIDE_NOT_ALWAYS);
					return true;
				} catch (NullPointerException ignored) {}
			}
			return false;
		});
		
		// 監聽輸入框的輸入 // 僅用於功能測試
		inputEditText.addTextChangedListener(new TextWatcher() { // 用來根據蒐字/蒐音變按鈕色
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {}
			@Override
			public void afterTextChanged(Editable s) {
				int presentColor = (StringUtil.isAlphaString(s.toString())) ?
						getResources().getColor(R.color.colorSecondary) :
						getResources().getColor(R.color.colorPrimary);
				if (previousColor == presentColor) return;
				ObjectAnimator objectAnimator;
				objectAnimator = ObjectAnimator.ofInt(btnQueryConfirm,"textColor", previousColor, presentColor);
				objectAnimator.setDuration(500);
				objectAnimator.setEvaluator(new ArgbEvaluator());
				objectAnimator.start();
				previousColor = presentColor;
			}
		});
		previousColor = getResources().getColor(R.color.colorPrimary);
		
		// 讀取幾個開關之前的狀態
		sp = getSharedPreferences("settings", MODE_PRIVATE);
		switchQueryOpts1.setChecked(sp.getBoolean("switch_1_is_checked", false));
		switchQueryOpts2.setChecked(sp.getBoolean("switch_2_is_checked", false));
		switchQueryOpts3.setChecked(sp.getBoolean("switch_3_is_checked", false));
		sp.getBoolean("advanced_search", false);
		lyAdvancedSearch.setVisibility(sp.getBoolean("advanced_search", false) ? View.VISIBLE : View.GONE);
		switchQueryOpts1.setOnCheckedChangeListener((buttonView, isChecked) -> setSearchView());
		switchQueryOpts2.setOnCheckedChangeListener((buttonView, isChecked) -> setSearchView());
		setSearchView();
		
		// 獲取泛粵字表的表頭
		query.setUrl("http://jyutdict.org/api/v0.9/sheet?query=&header")
			.setHandler(mainHandler, EnumConst.INITIALIZE_LOCATIONS)
			.start();
		printTipsMessageBox();
	}
	
	/**
	 * 設置幾個開關的顯示與隱藏
	 */
	private void setSearchView() {
		boolean is1Checked = switchQueryOpts1.isChecked();
		boolean is2Checked = switchQueryOpts2.isChecked();
		String switch1Text;
		if (is1Checked) {
			switch1Text = getString(R.string.search_jyut_sheet);
			switchQueryOpts2.setVisibility(View.VISIBLE);
			int spinnerVisibility = is2Checked ? View.INVISIBLE : View.VISIBLE;
			spinnerQueryLocation.setVisibility(spinnerVisibility);
		} else {
			switch1Text = getString(R.string.search_common_sheet);
			switchQueryOpts2.setVisibility(View.INVISIBLE);
			spinnerQueryLocation.setVisibility(View.INVISIBLE);
		}
		switchQueryOpts1.setText(switch1Text);
		switchQueryOpts3.setEnabled(is1Checked);
	}
	
	/**
	 * 設置標題欄右側的按鈕
	 */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.action_menu, menu);
		return true;
	}
	
	/**
	 * 響應標題欄右側按鈕的按下事件
	 *
	 * REQUESTING_SETTING 表示打開設置界面的 request code
	 */
	private static final int REQUESTING_SETTING = 8308;
	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		Intent intent;
		switch (item.getItemId()) {
			case R.id.menu_setting:
				intent = new Intent(MainActivity.this, SettingsActivity.class);
				startActivityForResult(intent, REQUESTING_SETTING);
				break;
			case R.id.menu_info:
				intent = new Intent(MainActivity.this, InfoActivity.class);
				startActivity(intent);
				break;
			default:
				break;
		}
		return super.onOptionsItemSelected(item);
	}
	
	/**
	 * onDestroy()
	 * 儲存主頁面幾個開關與下拉欄的的狀態，再退出 app
	 */
	@Override
	protected void onDestroy() {
		// 儲存當前設置
		SharedPreferences.Editor editor = sp.edit();
		editor.putBoolean("switch_1_is_checked", switchQueryOpts1.isChecked());
		editor.putBoolean("switch_2_is_checked", switchQueryOpts2.isChecked());
		editor.putBoolean("switch_3_is_checked", switchQueryOpts3.isChecked());
		editor.putInt("spinner_selected_position", spinnerQueryLocation.getSelectedItemPosition());
		editor.apply();
		super.onDestroy();
	}
	
	/**
	 * 更新輸入框中的字符串到 {@code this.inputString} 中
	 *
	 * 在將發起查詢時調用
	 *
	 * @param string 輸入框中的字符串
	 */
	private void setInputString(String string) {
		try {
			inputString = new String(string.getBytes("UTF-8"), "UTF-8")
					.replace('&',' ');
		} catch (UnsupportedEncodingException ignored) {}
	}
	
	/**
	 * 用指定字符串以指定模式發起查詢
	 *
	 * 該方法是對其它類開放的，可以在其它地方調用
	 * 將會改動主界面的開關
	 *
	 * @param chara 包含查詢內容的字符串
	 * @param mode 模式（通用表查字/查音/查泛粵表 等），可選值在 {@code EnumConst} 類定義
	 * @see EnumConst
	 */
	void search(String chara, int mode) {
		inputEditText.setText(chara);
		switch (mode) { // 爲了方便以後增加不同的查詢模式，這裏 switch 不能化簡
			case EnumConst.QUERYING_CHARA:
			case EnumConst.QUERYING_PRON:
				switchQueryOpts1.setChecked(false);
				break;
			case EnumConst.QUERYING_SHEET:
				switchQueryOpts1.setChecked(true);
				break;
		}
		switchQueryOpts2.setChecked(false);
		search();
	}
	
	/**
	 * 向服務器發起查詢
	 *
	 * 模式由主界面的開關指定，查詢內容由 {@code this.inputString} 指定
	 * 在等待回應時會禁用查詢按鈕
	 *
	 */
	private void search() {
		if (!isPrepared) {
			ToastUtil.msg(this, "正在獲取地方信息，請稍候");
			query.start(); // 重新向服務器發送請求
			return;
		}
		setInputString(inputEditText.getText().toString()); // 必须放在最前面
		ResultItemAdapter.ResultInfo.clearItem();
		loadingProgressBar.setVisibility(View.VISIBLE);
		StringBuilder url = new StringBuilder(URL_API_ROOT);
		if (switchQueryOpts1.isChecked()) { // 檢索泛粵字表
			queryObjectWhat = EnumConst.QUERYING_SHEET;
			if (StringUtil.isAlphaString(inputString) && !switchQueryOpts2.isChecked()) { // 音
				url.append(String.format("sheet?query=%s&trim", inputString));
				
			} else { // 字
				url.append(String.format("sheet?query=%s&fuzzy", inputString));
			}
			if (switchQueryOpts2.isChecked()) { // 反查
				url.append("&b");
			}
			int selectedColumn = spinnerQueryLocation.getSelectedItemPosition();
			switch (selectedColumn) {
				case 0:
					break;
				case 1:
					url.append("&col=").append(HeaderInfo.COLUMN_NAME_CONVENTIONAL);
					break;
				default:
					String col = HeaderInfo.getCityNameByNumber(selectedColumn-2);
					url.append("&col=").append(col);
			}
			if (switchQueryOpts3.isChecked()) {
				url.append("&regex");
			}
		} else { // 檢索通用字表
			if (StringUtil.isAlphaString(inputString)) { // 音
				queryObjectWhat = EnumConst.QUERYING_PRON;
				url.append("detail?pron=").append(inputString);
			} else { // 字
				queryObjectWhat = EnumConst.QUERYING_CHARA;
				url.append("detail?chara=").append(inputString);
			}
		}
		
		query.setUrl(url.toString()) // GET請求服務器API
			.setHandler(mainHandler)
			.start();
		btnQueryConfirm.setEnabled(false);
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQUESTING_SETTING) {
			boolean isEnableAdvancedSearch = (resultCode&0b1) != 0;
			lyAdvancedSearch.setVisibility(isEnableAdvancedSearch ? View.VISIBLE : View.GONE);
			if (!isEnableAdvancedSearch) switchQueryOpts3.setChecked(false);
		}
	}
	
	/**
	 * 首次使用時顯示提示框
	 */
	private void printTipsMessageBox() {
		boolean hadCheckedInfoActivity = sp.getBoolean("had_checked_info_activity", false);
		if (hadCheckedInfoActivity) return;
		new AlertDialog.Builder(this)
				.setTitle("歡迎使用本應用！")
				.setMessage("在使用之前，請務必閱覽本應用之說明。\n\n起碼把紅字看完！")
				.setPositiveButton("打開「幫助」頁面",
						(dialogInterface, i) -> {
							startActivity(new Intent(MainActivity.this, InfoActivity.class));
							sp.edit().putBoolean("had_checked_info_activity", true).apply();
						})
				.setCancelable(false)
				.show();
	}
	
	/**
	 * 申請網絡等權限
	 *
	 * 在初始化 app 時調用
	 */
	private void initPermission() {
		String[] permissions = {
				Manifest.permission.INTERNET,
		};
		ArrayList<String> toApplyList = new ArrayList<>();
		
		for (String perm : permissions) {
			ContextCompat.checkSelfPermission(this, perm);
			if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(this, perm)) {
				toApplyList.add(perm);
			}
		}
		String[] tmpList = new String[toApplyList.size()];
		if (!toApplyList.isEmpty()) {
			ActivityCompat.requestPermissions(this, toApplyList.toArray(tmpList), 123);
		}
	}
	
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (grantResults.length == 0 || grantResults[0]!=PackageManager.PERMISSION_GRANTED) {
			ToastUtil.msg(this, getString(R.string.permission_requesting));
			initPermission();
		}
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
	}
}
