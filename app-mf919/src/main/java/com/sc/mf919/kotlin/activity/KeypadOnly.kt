package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sc.mf919.R

class KeypadOnly : LinearLayout, View.OnClickListener {
    //private var mListener: onKeypadEventListener? = null
    lateinit var button_0: Button
    lateinit var button_1: Button
    lateinit var button_2: Button
    lateinit var button_3: Button
    lateinit var button_4: Button
    lateinit var button_5: Button
    lateinit var button_6: Button
    lateinit var button_7: Button
    lateinit var button_8: Button
    lateinit var button_9: Button
    lateinit var button_00: Button
    lateinit var button_del: Button

    //    private Button button_dot;
    //    private LinearLayout button_OK;
    //    private LinearLayout button_back;
    //    private LinearLayout button_gen_qr;
    //    private LinearLayout button_card;
    //    private LinearLayout button_qr_scan;
    // Passin from Activity use keypad
    private var msg = ""
    private var isAmount = false
    private var maxLen = 12
    private var textView: TextView? = null

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    private fun init(context: Context) {
        LayoutInflater.from(context).inflate(R.layout.activity_keypad_only, this, true)
        button_0 = findViewById<Button>(R.id.button_0)
        button_1 = findViewById<Button>(R.id.button_1)
        button_2 = findViewById<Button>(R.id.button_2)
        button_3 = findViewById<Button>(R.id.button_3)
        button_4 = findViewById<Button>(R.id.button_4)
        button_5 = findViewById<Button>(R.id.button_5)
        button_6 = findViewById<Button>(R.id.button_6)
        button_7 = findViewById<Button>(R.id.button_7)
        button_8 = findViewById<Button>(R.id.button_8)
        button_9 = findViewById<Button>(R.id.button_9)
        button_00 = findViewById<Button>(R.id.button_00)
        button_del = findViewById<Button>(R.id.button_delete_icon)

        //        button_dot = findViewById(R.id.button_dot);
//        button_OK = findViewById(R.id.button_OK);
//        button_back = findViewById(R.id.button_back_icon);

//        button_gen_qr = findViewById(R.id.button_gen_qr);
//        button_card = findViewById(R.id.button_card);
//        button_qr_scan = findViewById(R.id.button_qr_scan);
        button_0.setOnClickListener(this)
        button_1.setOnClickListener(this)
        button_2.setOnClickListener(this)
        button_3.setOnClickListener(this)
        button_4.setOnClickListener(this)
        button_5.setOnClickListener(this)
        button_6.setOnClickListener(this)
        button_7.setOnClickListener(this)
        button_8.setOnClickListener(this)
        button_9.setOnClickListener(this)
        button_00.setOnClickListener(this)
        button_del.setOnClickListener(this)

        //        button_dot.setOnClickListener(this);
//        button_OK.setOnClickListener(this);
//        button_back.setOnClickListener(this);

//        button_gen_qr.setOnClickListener(this);
//        button_card.setOnClickListener(this);
//        button_qr_scan.setOnClickListener(this);
        msg = ""
        isAmount = false
        maxLen = 12
    }

    @SuppressLint("NonConstantResourceId")
    override fun onClick(view: View) {
        when (view.id) {
            R.id.button_0 -> insertValue("0")
            R.id.button_1 -> insertValue("1")
            R.id.button_2 -> insertValue("2")
            R.id.button_3 -> insertValue("3")
            R.id.button_4 -> insertValue("4")
            R.id.button_5 -> insertValue("5")
            R.id.button_6 -> insertValue("6")
            R.id.button_7 -> insertValue("7")
            R.id.button_8 -> insertValue("8")
            R.id.button_9 -> insertValue("9")
            R.id.button_00 -> insertValue("00")
            R.id.button_delete_icon -> deleteValue()
            else -> visibleKeypad(true)
        }
    }

    private fun insertValue(value: String) {
        var previousText = msg
        if (previousText.length >= maxLen) {
            return
        }
        if (isAmount) {
            previousText = previousText.replace(".", "")
            if (previousText[0] == '0') {
                if (value === "00") {
                    if (previousText.length >= 3) {
                        println(previousText)
                        previousText = previousText.substring(1)
                        if (previousText[0] == '0') {
                            previousText = previousText.substring(1)
                        }
                    } else {
                        previousText = previousText.substring(2)
                    }
                } else {
                    previousText = previousText.substring(1)
                }
            }
            var newText = previousText + value
            val newText1 = StringBuilder(newText)
            newText1.insert(newText1.length - 2, ".")
            newText = newText1.toString()
            msg = newText
        } else {
            msg = previousText + value
        }

        if (textView == null) {
            println("mListener ???")
            //mListener.onOK(false, msg)
        } else {
            textView!!.text = msg
        }
    }

    private fun deleteValue() {
        var previousText = msg.replace(" ", "")
        if (isAmount) {
            previousText = previousText.replace(".", "")
            if (previousText.length <= 3) {
                previousText = previousText.substring(0, previousText.length - 1)
                previousText = "0$previousText"
            } else {
                previousText = previousText.substring(0, previousText.length - 1)
            }
            val newText1 = StringBuilder(previousText)
            newText1.insert(newText1.length - 2, ".")
            msg = newText1.toString()
        } else {
            val len = previousText.length
            if (len != 0) {
                previousText = previousText.substring(0, len - 1)
            }
            msg = previousText
        }
        if (textView == null) {
            println("mListener ???")
            //mListener.onOK(false, msg)
        } else {
            textView!!.text = msg
        }
    }

    private fun visibleKeypad(visibility: Boolean) {
        if (!visibility) {
            button_0!!.visibility = INVISIBLE
            button_1!!.visibility = INVISIBLE
            button_2!!.visibility = INVISIBLE
            button_3!!.visibility = INVISIBLE
            button_4!!.visibility = INVISIBLE
            button_5!!.visibility = INVISIBLE
            button_6!!.visibility = INVISIBLE
            button_7!!.visibility = INVISIBLE
            button_8!!.visibility = INVISIBLE
            button_9!!.visibility = INVISIBLE
            button_00!!.visibility = INVISIBLE
            button_del!!.visibility = INVISIBLE
            //            button_dot.setVisibility(View.INVISIBLE);
//            button_OK.setVisibility(View.INVISIBLE);
//            button_back.setVisibility(View.INVISIBLE);
//            button_gen_qr.setVisibility(View.INVISIBLE);
//            button_card.setVisibility(View.INVISIBLE);
//            button_qr_scan.setVisibility(View.INVISIBLE);
        } else {
            button_0!!.visibility = VISIBLE
            button_1!!.visibility = VISIBLE
            button_2!!.visibility = VISIBLE
            button_3!!.visibility = VISIBLE
            button_4!!.visibility = VISIBLE
            button_5!!.visibility = VISIBLE
            button_6!!.visibility = VISIBLE
            button_7!!.visibility = VISIBLE
            button_8!!.visibility = VISIBLE
            button_9!!.visibility = VISIBLE
            button_00!!.visibility = VISIBLE
            button_del!!.visibility = VISIBLE
            //            button_dot.setVisibility(View.VISIBLE);
//            button_OK.setVisibility(View.VISIBLE);
//            button_back.setVisibility(View.VISIBLE);
//            button_gen_qr.setVisibility(View.VISIBLE);
//            button_card.setVisibility(View.VISIBLE);
//            button_qr_scan.setVisibility(View.VISIBLE);
        }
    }

    fun setFilter(tv: TextView?, _isAmount: Boolean, _maxLen: Int) {
        isAmount = _isAmount
        maxLen = _maxLen
        if (isAmount) {
            maxLen++
            msg = "0.00"
        }
        textView = tv
        msg = textView!!.text.toString()
        textView!!.setOnClickListener(this)
    }

    fun setFilter(_isAmount: Boolean, _maxLen: Int) {
        isAmount = _isAmount
        maxLen = _maxLen
        if (isAmount) {
            maxLen++
            msg = "0.00"
        }
    }

    val textViewID: Int
        get() = if (textView != null) {
            textView!!.id
        } else {
            0
        }
}