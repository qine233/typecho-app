package com.typecho.app.ui.components

import com.typecho.app.data.remote.NolineIssue

/**
 * 接口每种状态各说一句准确的话，健康页和设置页共用一份，免得两处对同一种故障说法不一致。
 * 键值是接口文档 §3.1 / §6 的分支，不匹配服务器 message 文案。
 */
object NolineGuide {

    fun title(issue: NolineIssue?): String = when (issue) {
        NolineIssue.NoKey -> "还没有填接口密钥"
        NolineIssue.WrongKey -> "密钥不正确"
        NolineIssue.ReadOnly -> "站点是只读模式"
        NolineIssue.SiteOff -> "站点那边没开接口"
        NolineIssue.BadPath -> "找不到健康接口"
        NolineIssue.BadMethod -> "接口调用方式有误"
        NolineIssue.BadInput -> "提交的内容没通过校验"
        NolineIssue.StoreUnwritable -> "服务器数据目录不可写"
        NolineIssue.Network -> "现在连不上站点"
        NolineIssue.Unknown -> "接口回了话，App 没读懂"
        else -> "健康接口还没连通"
    }

    fun body(issue: NolineIssue?): String = when (issue) {
        NolineIssue.NoKey ->
            "博客后台「插件 → NolineApi → 设置」里生成密钥，填到 App 设置的健康接口一栏。"
        NolineIssue.WrongKey ->
            "站点拒绝了这把密钥。后台重新生成一次，再复制到 App 里。"
        NolineIssue.ReadOnly ->
            "数据能看不能改。到后台「NolineApi → 设置」把「允许写入」打开，再回来同步。"
        NolineIssue.SiteOff ->
            "后台「NolineApi → 设置」里的「对外接口」总开关处于关闭状态，或密钥还没设置。"
        NolineIssue.BadPath ->
            "站点上没装 NolineApi 插件，或者地址填错了。"
        NolineIssue.BadMethod ->
            "这是客户端的问题，重试没有用。"
        NolineIssue.BadInput ->
            "检查日期和数字，改完再提交。"
        NolineIssue.StoreUnwritable ->
            "让站点管理员检查 usr/uploads/noline_api 的权限，重试没有用。"
        NolineIssue.Network ->
            "记录会先存在手机里，等有网自动补交。"
        NolineIssue.Unknown ->
            "站点回了话，但 App 没能读懂这份响应——多半是字段类型和接口文档对不上。"
        else -> "先到设置页做一次自检，确认站点和密钥都对。"
    }
}
