package com.sc.mf919pro.kotlin.fragment

import android.content.Context
import android.view.*
import android.widget.*
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.bumptech.glide.Glide
import com.sc.mf919pro.R

class ImageSlideAdapter(private val context: Context, private var imageList: List<String>) : PagerAdapter() {
    override fun getCount(): Int {
        return imageList.size
    }

    override fun isViewFromObject(view: View, `object`: Any): Boolean {
        return view === `object`
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val view: View =  (context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater).inflate(R.layout.image_slider_item, null)
        val ivImages = view.findViewById<ImageView>(R.id.iv_images)

        val imageUrl = imageList[position]
        if (imageUrl.startsWith("defaultBanner")) {
            ivImages.setImageResource(R.drawable.default_temporary_banner)
        } else {
            Glide.with(context)
                    .load(imageUrl)
                    .into(ivImages)
        }


        val vp = container as ViewPager
        vp.addView(view, 0)
        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        val vp = container as ViewPager
        val view = `object` as View
        vp.removeView(view)
    }
}