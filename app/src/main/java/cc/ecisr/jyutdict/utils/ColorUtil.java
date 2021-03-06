package cc.ecisr.jyutdict.utils;

import android.graphics.Color;

/**
 * ColorUtil 類，用於存放處理顏色相關的函數
 */
public class ColorUtil {
	private static final String TAG = "`ColorUtil";
	
	/**
	 * 獲取顏色亮度
	 * @param colorString 表示RGB顏色的十六進制字符串，如"#FFFFFF"
	 * @return double 格式，表示顏色的亮度，範圍從 0~252.705
	 */
	public static double getLightness(String colorString) {
		int color = Color.parseColor(colorString);
		double r = Color.red(color);
		double g = Color.green(color);
		double b = Color.blue(color);
		return r*0.299 + g*0.578 + b*0.114;
	}
	
	/**
	 * 將輸入顏色的明度乘以一個係數再返回
	 * 以調節該顏色明度
	 * 爲了保持顏色的飽和度，明度在乘以係數的同時，飽和度除以該係數的平方
	 * 如，係數爲 0.5 時，明度降爲一半，飽和度昇爲四倍
	 *
	 * @param colorString 表示顏色的十六進制字符串，如"#FFFFFFF"
	 * @param ratio 明度係數
	 * @return 以整形數字表示的顏色代碼
	 */
	public static int darken(String colorString, double ratio) {
		int color = Color.parseColor(colorString);
		float[] hsv = new float[3];
		Color.colorToHSV(color, hsv);
		hsv[2] *= ratio;
		hsv[1] /= ratio*ratio;
		return Color.HSVToColor(hsv);
	}
}
