package spam.blocker.service

import android.content.Context
import android.util.Log
import spam.blocker.db.CallTable
import spam.blocker.db.ContentFilterTable
import spam.blocker.db.Db
import spam.blocker.db.NumberFilterTable
import spam.blocker.db.PatternFilter
import spam.blocker.db.SmsTable
import spam.blocker.def.Def
import spam.blocker.util.Contacts
import spam.blocker.util.Permission
import spam.blocker.util.SharedPref
import spam.blocker.util.Util

class CheckResult(
    val shouldBlock: Boolean,
    val result: Int,
) {
    var byContactName: String? = null // allowed by contact
    var byFilter: PatternFilter? = null // allowed or blocked by this filter rule
    var byRecentApp: String? = null // allowed by recent app

    fun reason(): String {
        if (byContactName != null) return byContactName!!
        if (byFilter != null) return byFilter!!.id.toString()
        if (byRecentApp != null) return byRecentApp!!
        return ""
    }

    fun setContactName(name: String): CheckResult {
        byContactName = name
        return this
    }

    fun setFilter(f: PatternFilter): CheckResult {
        byFilter = f
        return this
    }

    fun setRecentApp(pkg: String): CheckResult {
        byRecentApp = pkg
        return this
    }
}

interface checker {
    fun priority(): Int
    fun check(): CheckResult?
}

class Checker { // for namespace only

    class Contact(private val ctx: Context, private val rawNumber: String) : checker {
        override fun priority(): Int {
            val isExclusive = SharedPref(ctx).isContactExclusive()
            return if (isExclusive) 0 else 10
        }

        override fun check(): CheckResult? {
            val spf = SharedPref(ctx)

            if (!spf.isContactEnabled()) {
                return null
            }
            val contact = Contacts.findByRawNumberAuto(ctx, rawNumber)
            if (contact != null) {
                Log.i(Def.TAG, "is contact")
                return CheckResult(
                    false,
                    Def.RESULT_ALLOWED_BY_CONTACT
                ).setContactName(contact.name)
            } else {
                if (spf.isContactExclusive()) {
                    return CheckResult(true, Def.RESULT_BLOCKED_BY_NON_CONTACT)
                }
            }
            return null
        }
    }

    class RepeatedCall(private val ctx: Context, private val rawNumber: String) : checker {
        override fun priority(): Int {
            return 10
        }

        override fun check(): CheckResult? {
            val spf = SharedPref(ctx)
            if (!spf.isRepeatedAllowed()) {
                return null
            }
            val (times, durationMinutes) = spf.getRepeatedConfig()

            // repeated count of call/sms, sms also counts
            val nCalls = CallTable().countRepeatedRecordsWithinSeconds(ctx, rawNumber, durationMinutes*60)
            val nSMSs = SmsTable().countRepeatedRecordsWithinSeconds(ctx, rawNumber, durationMinutes*60)
            if (nCalls + nSMSs >= times) {
                Log.d(Def.TAG, "allowed by repeated call")
                return CheckResult(false, Def.RESULT_ALLOWED_BY_REPEATED)
            }
            return null
        }
    }

    class RecentApp(private val ctx: Context) : checker {
        override fun priority(): Int {
            return 10
        }

        override fun check(): CheckResult? {
            val spf = SharedPref(ctx)

            val enabledPackages = spf.getRecentAppList()
            if (enabledPackages.isEmpty()) {
                return null
            }
            val inXmin = spf.getRecentAppConfig()
            val usedApps = Permission.listUsedAppWithinXSecond(ctx, inXmin * 60)

            val intersection = enabledPackages.intersect(usedApps.toSet())
            Log.d(
                Def.TAG,
                "--- enabled: $enabledPackages, used: $usedApps, intersection: $intersection"
            )

            if (intersection.isNotEmpty()) {
                Log.d(Def.TAG, "allowed by recent used app")
                return CheckResult(
                    false,
                    Def.RESULT_ALLOWED_BY_RECENT_APP
                ).setRecentApp(intersection.first())
            }
            return null
        }
    }

    /*
        Check if a number rule matches the incoming number
     */
    class Number(private val rawNumber: String, private val filter: PatternFilter) : checker {
        override fun priority(): Int {
            return filter.priority
        }

        override fun check(): CheckResult? {
            if (filter.pattern.toRegex().matches(Util.clearNumber(rawNumber))) {
                val block = filter.isBlacklist
                return CheckResult(
                    block,
                    if (block) Def.RESULT_BLOCKED_BY_NUMBER else Def.RESULT_ALLOWED_BY_NUMBER
                ).setFilter(filter)
            }

            return null
        }
    }
    /*
        Check if text message body matches the SMS Content rule,
        the number is also checked when "for particular number" is enabled
     */
    class Content(private val rawNumber: String, private val messageBody: String, private val filter: PatternFilter) : checker {
        override fun priority(): Int {
            return filter.priority
        }

        override fun check(): CheckResult? {
            val f = filter // for short

            val matches = if (filter.patternExtra != "") { // for particular number enabled
                f.pattern.toRegex().matches(messageBody)
                        && f.patternExtra.toRegex() .matches(Util.clearNumber(rawNumber))
            } else {
                f.pattern.toRegex().matches(messageBody)
            }

            if (matches) {
                Log.d(Def.TAG, "filter matches: $f")

                val block = f.isBlacklist

                return CheckResult(
                    block,
                    if (block) Def.RESULT_BLOCKED_BY_CONTENT else Def.RESULT_ALLOWED_BY_CONTENT
                ).setFilter(f)
            }
            return null
        }
    }
}


class SpamChecker {
    companion object {

        fun checkCall(ctx: Context, rawNumber: String): CheckResult {
            val checkers = arrayListOf(
                Checker.Contact(ctx, rawNumber),
                Checker.RepeatedCall(ctx, rawNumber),
                Checker.RecentApp(ctx)
            )
            //  add number rules to checkers
            val filters = NumberFilterTable().listFilters(
                ctx, Db.FLAG_FOR_CALL, Db.FLAG_BOTH_WHITE_BLACKLIST)
            checkers += filters.map {
                Checker.Number(rawNumber, it)
            }

            // sort by priority desc
            checkers.sortByDescending {
                it.priority()
            }

            // try all checkers in order, until a match is found
            var result: CheckResult? = null
            checkers.firstOrNull {
                result = it.check()
                result != null
            }
            // match found
            if (result != null) {
                return result!!
            }

            // pass by default
            return CheckResult(false, Def.RESULT_ALLOWED_BY_DEFAULT)
        }

        fun checkSms(
            ctx: Context,
            rawNumber: String,
            messageBody: String
        ): CheckResult {

            val checkers = arrayListOf<checker>(
                Checker.Contact(ctx, rawNumber),
            )

            //  add number rules to checkers
            val numberFilters = NumberFilterTable().listFilters(
                ctx, Db.FLAG_FOR_SMS, Db.FLAG_BOTH_WHITE_BLACKLIST
            )
            checkers += numberFilters.map {
                Checker.Number(rawNumber, it)
            }

            //  add sms content rules to checkers
            val contentFilters = ContentFilterTable().listFilters(
                ctx, 0/* doesn't care */, Db.FLAG_BOTH_WHITE_BLACKLIST
            )
            checkers += contentFilters.map {
                Checker.Content(rawNumber, messageBody, it)
            }

            // sort by priority desc
            checkers.sortByDescending {
                it.priority()
            }

            // try all checkers in order, until a match is found
            var result: CheckResult? = null
            checkers.firstOrNull {
                result = it.check()
                result != null
            }
            // match found
            if (result != null) {
                return result!!
            }

            // pass by default
            return CheckResult(false, Def.RESULT_ALLOWED_BY_DEFAULT)
        }
    }
}