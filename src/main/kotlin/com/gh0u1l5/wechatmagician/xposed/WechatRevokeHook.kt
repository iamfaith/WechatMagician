package com.gh0u1l5.wechatmagician.xposed

import android.content.ContentValues
import android.content.res.XModuleResources
import com.gh0u1l5.wechatmagician.R
import com.gh0u1l5.wechatmagician.util.MessageUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge.log
import de.robv.android.xposed.XposedHelpers.findAndHookMethod

class WechatRevokeHook(private val ver: WechatVersion, private val res: XModuleResources) {

    private var msgTable: List<WechatMessage> = listOf()

    fun hook(loader: ClassLoader?) {
        try {
            hookDatabase(loader)
            hookRevoke(loader)
        } catch(e: NoSuchMethodError) {
            when {
                e.message!!.contains(ver.SQLiteDatabaseClass) -> {
                    log("NSME => ${ver.SQLiteDatabaseClass}")
                    XpWechat._ver?.SQLiteDatabaseClass = ""
                }
                e.message!!.contains("${ver.recallClass}#${ver.recallMethod}") -> {
                    log("NSME => ${ver.recallClass}#${ver.recallMethod}")
                    XpWechat._ver?.recallClass = ""
                    XpWechat._ver?.recallMethod = ""
                }
                else -> throw e
            }
        }
    }

    @Synchronized
    private fun addMessage(msgId: Long, type: Int, talker: String, content: String) {
        msgTable += WechatMessage(msgId, type, talker,content)
    }

    @Synchronized
    private fun getMessage(msgId: Long): WechatMessage? {
        return msgTable.find { it.msgId == msgId }
    }

    @Synchronized
    private fun cleanMessage() {
        msgTable = msgTable.filter { System.currentTimeMillis() - it.time < 120000 }
    }

    @Suppress("UNCHECKED_CAST")
    private fun hookRevoke(loader: ClassLoader?) {
        if (ver.recallClass == "" || ver.recallMethod == "") {
            return
        }
        findAndHookMethod(ver.recallClass, loader, ver.recallMethod, String::class.java, String::class.java, object: XC_MethodHook() {
            @Throws(Throwable::class)
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = (param.result as? MutableMap<String, String?>)?.apply {
                    if (this[".sysmsg.\$type"] != "revokemsg"){
                        return
                    }
                    val replacemsg = this[".sysmsg.revokemsg.replacemsg"]
                    this[".sysmsg.revokemsg.replacemsg"] = replacemsg?.let {
                        if (it.startsWith("你") || it.startsWith("you", true)) it
                        else MessageUtil.customize(it, res.getString(R.string.easter_egg))
                    }
                }
            }
        })
    }

    private fun hookDatabase(loader: ClassLoader?) {
        if (ver.SQLiteDatabaseClass == ""){
            return
        }

        findAndHookMethod(ver.SQLiteDatabaseClass, loader, "insertWithOnConflict", String::class.java, String::class.java, ContentValues::class.java, Integer.TYPE, object: XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val table = param.args[0] as String?
                val initialValues = param.args[2] as ContentValues?

                if (table == "message") {
                    initialValues?.apply {
                        if (!containsKey("type") || !containsKey("talker")) {
                            log("DB => skewed message $initialValues")
                            return
                        }
                        addMessage(
                                getAsLong("msgId"),
                                getAsInteger("type"),
                                getAsString("talker"),
                                getAsString("content"))
                    }
                    cleanMessage()
                }
            }
        })

        findAndHookMethod(ver.SQLiteDatabaseClass, loader, "updateWithOnConflict", String::class.java, ContentValues::class.java, String::class.java, Array<String?>::class.java, Integer.TYPE, object: XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val table = param.args[0] as String?
                val values = param.args[1] as ContentValues?
//                val whereClause = param.args[2] as String?
//                val whereArgs = param.args[3] as Array<*>?
//                log("DB => update table = $table, values = $values, whereClause = $whereClause, whereArgs = ${MessageUtil.argsToString(whereArgs)}")

                val label_recalled = res.getString(R.string.label_recalled)
                val label_deleted = res.getString(R.string.label_deleted)

                when (table) {
                    "message" -> values?.apply {
                        if (!containsKey("type") || this["type"] != 10000){
                            return
                        }
                        val sysMsg = this["content"] as String
                        if (sysMsg.startsWith("你") || sysMsg.startsWith("you", true)) {
                            return
                        }
                        remove("content"); remove("type")
                        getMessage(this["msgId"] as Long)?.let {
                            when (it.type) {
                                1 -> {
                                    if (it.talker.contains("chatroom"))
                                        put("content", MessageUtil.notifyChatroomMsgRecall(label_recalled, it.content))
                                    else
                                        put("content", MessageUtil.notifyPrivateMsgRecall(label_recalled, it.content))
                                }
                                49 -> put("content", MessageUtil.notifyLinkRecall(label_recalled, it.content))
                            }
                        }
                    }
                    "SnsInfo" -> values?.apply {
                        if (!containsKey("sourceType") || this["sourceType"] != 0){
                            return
                        }
                        remove("sourceType")
                        put("content", MessageUtil.notifyInfoDelete(label_deleted, this["content"] as ByteArray))
                    }
                    "SnsComment" -> values?.apply {
                        if (!containsKey("type") || this["type"] == 1){
                            return
                        }
                        if (!containsKey("commentflag") || this["commentflag"] != 1){
                            return
                        }
                        remove("commentflag")
                        put("curActionBuf", MessageUtil.notifyCommentDelete(label_deleted, this["curActionBuf"] as ByteArray))
                    }
                }
            }
        })

//        findAndHookMethod(ver.SQLiteDatabaseClass, loader, "delete", String::class.java, String::class.java, Array<String?>::class.java, object: XC_MethodHook() {
//            @Throws(Throwable::class)
//            override fun beforeHookedMethod(param: MethodHookParam) {
//                val p1 = param.args[0] as String?
//                val p2 = param.args[1] as String?
//                val p3 = param.args[2] as Array<*>?
//                log("DB => delete p1 = $p1, p2 = $p2, p3 = ${MessageUtil.argsToString(p3)}")
//            }
//        })

//        findAndHookMethod(ver.SQLiteDatabaseClass, loader, "executeSql", String::class.java, Array<Any?>::class.java, object: XC_MethodHook() {
//            @Throws(Throwable::class)
//            override fun beforeHookedMethod(param: MethodHookParam) {
//                val p1 = param.args[0] as String?
//                val p2 = param.args[1] as Array<*>?
//                log("DB => executeSqlxecSQL p1 = $p1, p2 = ${MessageUtil.argsToString(p2)}")
//            }
//        })
    }
}