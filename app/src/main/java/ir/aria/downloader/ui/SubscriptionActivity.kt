package ir.aria.downloader.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ir.aria.downloader.R
import ir.aria.downloader.databinding.ActivitySubscriptionBinding

/** Placeholder subscription page — fake/demo prices for now, corrected later. */
class SubscriptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubscriptionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        val soon = { Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show() }
        binding.monthlyButton.setOnClickListener { soon() }
        binding.yearlyButton.setOnClickListener { soon() }
    }
}
