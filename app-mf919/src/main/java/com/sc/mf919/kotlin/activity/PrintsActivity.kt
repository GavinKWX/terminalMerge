package com.sc.mf919.kotlin.activity

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.RemoteException
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.morefun.yapi.device.printer.OnPrintListener
import com.morefun.yapi.device.printer.PrinterConfig
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.device.DeviceHelper
import utils.HexUtil
import java.io.File
import java.io.IOException
import java.util.*

class PrintsActivity : AppCompatActivity() {
    var whomCps = 0
    private var LL: LinearLayout? = null
    private val closeInactivated = false
    private val cardPresent = false
    private val isoPrint = true
    private var inv: String? = null
    private var amount: String? = null
    var alertDialog: AlertDialog? = null
    private val fontPath = "/storage/emulated/0/Android/data/com.morefun.ysdk.sample/cache/wawa.ttf"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //if(KioskConstant.isAttend()==1){new autoClose().execute();}
        setContentView(R.layout.activity_printreceipt)
        val details_1 = receiptDetails()
        val tv = arrayOfNulls<TextView>(details_1.size)
        LL = findViewById(R.id.tableLayout2TR)
        formLayout(LL, tv, details_1)
        //cardDetection();
        //print(1);
    }

    private fun formLayout(ll: LinearLayout?, tv: Array<TextView?>, details: Array<String?>) {
        var Amo = 15
        if (details.size == 21) {
            Amo = 17
        }
        for (j in details.indices) {
            tv[j] = TextView(applicationContext)
            tv[j]!!.text = details[j]
            tv[j]!!.setTextColor(Color.parseColor("#000000"))
            tv[j]!!.textSize = 11f
            tv[j]!!.setPadding(20, 0, 20, 0)
            if (j == 6 || j == 8 || j == Amo) {
                tv[j]!!.textSize = 14f
                tv[j]!!.gravity = Gravity.CENTER_HORIZONTAL
                tv[j]!!.setTypeface(null, Typeface.BOLD)
                tv[j]!!.setPadding(20, 5, 20, 5)
                if (j == Amo) {
                    tv[j]!!.setTextColor(Color.parseColor("#0c20da"))
                }
            }
            if (j == Amo + 1) {
                tv[j]!!.gravity = Gravity.CENTER_HORIZONTAL
                tv[j]!!.setTypeface(null, Typeface.BOLD)
            }
            if (Amo + 2 == j) {
                tv[j]!!.textSize = 8f
                tv[j]!!.gravity = Gravity.CENTER_HORIZONTAL
                tv[j]!!.setPadding(20, 5, 20, 5)
            }
            ll!!.addView(tv[j])
        }
        if (false /*GeneralMethod.getFooter()*/) {
            val iv = ImageView(applicationContext)
            val hzLay = LinearLayout.LayoutParams(208, 30)
            hzLay.setMargins(0, 0, 0, 10)
            hzLay.gravity = Gravity.CENTER
            iv.layoutParams = hzLay
            //iv.setImageResource(R.mipmap.logo_5);
            ll!!.addView(iv)
        }
        print_animation(1)
    }

    fun customerCopy(view: View?) {
        //print(2);
        //if(GeneralMethod.getQRDisplayStatus()){QRDialog();}
        //new autoClose().execute();
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    fun print_animation(whom: Int) {
        whomCps = whom
        /*new Thread()
        {

            @TargetApi(Build.VERSION_CODES.N)
            @Override
            public void run()
            {
                Utils.debugLogPrint(TAG, "printing");
                printSaleReceipt(whomCps,isoPrint);
                isoPrint=false;
            }
        }.start();*/
        val animSlideDown = AnimationUtils.loadAnimation(applicationContext, R.anim.top_slide_out)
        LL!!.startAnimation(animSlideDown)
    }

    fun receiptDetails( /*String[] info*/): Array<String?> {
        var details = arrayOfNulls<String>(19)
        val year = Calendar.getInstance()[Calendar.YEAR]
        var cdNum = Utils.removeCarNumChar("123456789" /*info[0]*/)
        cdNum = Utils.hideCardDetails(cdNum)
        //if(!(info[14].equals(TransFieldConstant.payMethods.sMAG)||info[14].equals(TransFieldConstant.payMethods.sMan)))
        //{
        details = arrayOfNulls(21)
        //}
        (findViewById<View>(R.id.MerchantName) as TextView).text = "Test Merchant"
        (findViewById<View>(R.id.MerchantAdd) as TextView).text = "Addr 1"
        details[0] = getString(R.string.TID) + " " +  /*info[10]*/"92000001"
        details[1] = getString(R.string.MID) + " " +  /*info[9]*/"100000000000001"
        //details[2]=getString(R.string.TypeZakat) + " " + info[19] + " / " + info[20];
        details[2] =
            getString(R.string.Date) + " " +  /*Utils.DateFormat(year + info[6])*/"date" + " / " +  /*Utils.TimeFormat(info[7])*/"time"
        details[3] = getString(R.string.Batch) + " " +  /*info[5]*/"batch"
        details[4] = getString(R.string.Trace) + " " +  /*info[13]*/"trace"
        details[5] = getString(R.string.Invoice) + " " +  /*info[12]*/"invno"
        details[6] =  /*info[11]*/"info11"
        details[7] = getString(R.string.CardNo)
        //if(info[11].equals(TransFieldConstant.processType.preAuth)){cdNum=info[0];}
        details[8] = Utils.spaceBtwNoChar(cdNum, 4)
        details[9] = getString(R.string.EntryMode) + " " +  /*info[14]*/"entrymode"
        details[10] = getString(R.string.APP) + " " +  /*info[1]*/"app"
        details[11] = getString(R.string.ADI) + " " +  /*info[2]*/"adi"
        details[12] = getString(R.string.APPR) + " " +  /*info[4]*/"appr"
        details[13] = getString(R.string.RESP) + " " +  /*info[18]*/"resp"
        details[14] = getString(R.string.REF) + " " +  /*info[3]*/"ref"
        inv = details[14]
        //if(!(info[14].equals(TransFieldConstant.payMethods.sMAG)||info[14].equals(TransFieldConstant.payMethods.sMan)))
        //{
        details[15] = getString(R.string.AppC) + " " +  /*info[16]*/"appc"
        details[16] = getString(R.string.TVR) + " " +  /*info[17]*/"tvr"
        details[17] = getString(R.string.amount_currency) + " " +  /*info[8]*/"RM"
        amount = details[17]
        details[18] = "AAA" //GeneralMethod.CVMAnalysis(info[15],info[14]);
        details[19] = getString(R.string.msgRe)
        details[20] = ""
        //}
        //else
        //{
        details[15] = getString(R.string.amount_currency) + " " +  /*info[8]*/"$"
        amount = details[15]
        details[16] =
            "PIN VERIFIED\nNO SIGNATURE REQUIRED" //GeneralMethod.CVMAnalysis(info[15],info[14]);
        details[17] = getString(R.string.msgRe)
        details[18] = ""
        //}
        return details
    }

    private fun print() {
        try {
            val fontSize = FontFamily.MIDDLE
            val config = Bundle()
            config.putString(PrinterConfig.COMMON_TYPEFACE_PATH, fontPath)
            config.putInt(PrinterConfig.COMMON_GRAYLEVEL, 15)
            val list: MutableList<MulPrintStrEntity> = ArrayList()
            var entity = MulPrintStrEntity("POS purchase order", fontSize)
            val imageFromAssetsFile = getImageFromAssetsFile(this, "china_union_pay.bmp")
            entity.bitmap = imageFromAssetsFile
            entity.marginX = 50
            entity.gravity = Gravity.CENTER
            entity.isUnderline = true
            entity.yspace = 30
            list.add(entity)
            val mulPrintStrEntity = MulPrintStrEntity("=====================", fontSize)
            list.add(mulPrintStrEntity)
            list.add(MulPrintStrEntity("MERCHANT NAME：Demo shop name", fontSize))
            list.add(MulPrintStrEntity("MERCHANT NO.：20321545656687", fontSize))
            list.add(MulPrintStrEntity("TERMINAL NO.：25689753", fontSize))
            list.add(MulPrintStrEntity("CARD NUMBER", fontSize))
            list.add(MulPrintStrEntity("62179390*****3426", fontSize))
            list.add(MulPrintStrEntity("TRANS TYPE", fontSize))
            list.add(MulPrintStrEntity("SALE", fontSize))
            list.add(MulPrintStrEntity("EXP DATE：2029", fontSize))
            list.add(MulPrintStrEntity("BATCH NO：000012", fontSize))
            list.add(MulPrintStrEntity("VOUCHER NO：000001", fontSize))
            list.add(MulPrintStrEntity("DATE/TIME：2016-05-23 16:50:32", fontSize))
            list.add(MulPrintStrEntity("AMOUNT", fontSize))
            list.add(MulPrintStrEntity("==========================", fontSize))
            //feed pager one line
            list.add(MulPrintStrEntity("\n", fontSize))
            entity = MulPrintStrEntity("CARD HOLDER SIGNATURE", fontSize)
            list.add(entity)
            list.add(MulPrintStrEntity("\n", fontSize))
            list.add(MulPrintStrEntity("--------------------------------------", fontSize))
            list.add(
                MulPrintStrEntity(
                    " I ACKNOWLEDGE	SATISFACTORY RECEIPT OF RELATIVE GOODS/SERVICES",
                    fontSize
                )
            )
            list.add(MulPrintStrEntity(" MERCHANT COPY ", fontSize))
            list.add(MulPrintStrEntity("---X---X---X---X---X--X--X--X--X--X--\n", fontSize))
            list.add(MulPrintStrEntity("\n", fontSize))
            DeviceHelper.getPrinter().printStr(list, object : OnPrintListener.Stub() {
                @Throws(RemoteException::class)
                override fun onPrintResult(result: Int) {
                    runOnUiThread {
                        //button.setEnabled(true);
                    }
                    //showResult(textView, result == ServiceResult.Success ? getString(R.string.msg_succ) : getString(R.string.msg_fail));
                    //this.sysPrint(result == ServiceResult.Success ? getString(R.string.msg_succ) : getString(R.string.msg_fail));
                }
            }, config)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val TAG = "Print"
        private fun sysPrint(message: String) {
            Utils.debugLogPrint(TAG, message)
        }

        private fun sysPrint(message: String, data: ByteArray, dataOffset: Int, dataLen: Int) {
            //Utils.debugLogPrint(TAG, Utils.byteArrayToHexString(data, dataOffset, dataLen));
            Utils.debugLogPrint(TAG, message + HexUtil.bcd2str(data, dataOffset, dataLen))
        }

        fun getImageFromAssetsFile(context: Context, fileName: String?): Bitmap? {
            var image: Bitmap? = null
            val am = context.resources.assets
            try {
                val `is` = am.open(fileName!!)
                image = BitmapFactory.decodeStream(`is`)
                `is`.close()
            } catch (e: IOException) {
                e.printStackTrace()
                Utils.debugLogPrint("getImageFromAssetsFile", "Failed to load asset: $fileName")
            }
            return image
        }

        fun isExists(filePath: String?): Boolean {
            return !TextUtils.isEmpty(filePath) && File(filePath).exists()
        }
    }
}
