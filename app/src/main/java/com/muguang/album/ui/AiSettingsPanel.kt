@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.muguang.album.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.muguang.album.core.*

@Composable
fun AiSettingsPanel(settings:AiSettings,photos:List<Photo>,onSave:(AiSettings,String?)->Unit,onCommand:(String,String?)->Unit,onTest:()->Unit) {
    var endpoint by rememberSaveable(settings.endpoint) { mutableStateOf(settings.endpoint) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var secret by remember { mutableStateOf("") }
    var enabled by rememberSaveable(settings.enabled) { mutableStateOf(settings.enabled) }
    var limit by rememberSaveable(settings.maxImagesPerRun) { mutableStateOf(settings.maxImagesPerRun.toString()) }
    var tokenParameter by rememberSaveable(settings.tokenParameter) { mutableStateOf(settings.tokenParameter) }
    var testing by remember { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("AI 辅助识别",style=MaterialTheme.typography.titleMedium)
        Text("本地模型有争议时，发送完整构图的压缩副本到你配置的视觉服务。每张图一次处理分类与焦点，结果自动保存。",style=MaterialTheme.typography.bodySmall)
        Row { Text("启用 AI 识别",Modifier.weight(1f)); Switch(enabled,{enabled=it},Modifier.testTag("ai-enabled")) }
        OutlinedTextField(endpoint,{endpoint=it},label={Text("完整接口地址（HTTPS）")},placeholder={Text("https://服务地址/v1/chat/completions")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("ai-endpoint"))
        OutlinedTextField(model,{model=it},label={Text("视觉模型名称")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("ai-model"))
        OutlinedTextField(secret,{secret=it},label={Text(if(settings.hasKey) "API 密钥（已保存，留空保留）" else "API 密钥")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth().testTag("ai-key"))
        OutlinedTextField(limit,{limit=it.filter(Char::isDigit)},label={Text("每轮最多识别图片数")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
        Text("输出长度参数（按服务商文档选择）",style=MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("max_tokens" to "常用", "max_completion_tokens" to "新式", "none" to "不发送").forEach { (value,label) ->
                FilterChip(tokenParameter==value,{tokenParameter=value},label={Text(label)})
            }
        }
        Button(onClick={ onSave(settings.copy(endpoint=endpoint,model=model,enabled=enabled,maxImagesPerRun=limit.toIntOrNull() ?: 50,tokenParameter=tokenParameter),secret.takeIf { it.isNotBlank() }); secret="" },enabled=limit.toIntOrNull() in 1..10000,modifier=Modifier.fillMaxWidth().testTag("ai-save")) { Text("保存 AI 配置") }
        Text("本轮已发送 ${settings.sent} / ${settings.maxImagesPerRun} 张 · 待处理 ${photos.count { it.metadata.aiStatus in setOf("QUEUED","WAITING_CONFIG") }} 张",style=MaterialTheme.typography.bodySmall)
        Text("AI 已完成 ${photos.count { it.metadata.aiStatus=="SUCCEEDED" }} 张 · 待人工 ${photos.count { it.metadata.aiStatus=="UNRESOLVED" }} 张 · 调用失败 ${photos.count { it.metadata.aiStatus in setOf("FAILED","UNKNOWN_OUTCOME") }} 张",style=MaterialTheme.typography.bodySmall)
        Text(settings.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            TextButton({onCommand(if(settings.paused) "RESUME" else "PAUSE",null)}) { Text(if(settings.paused) "继续队列" else "暂停队列") }
            TextButton({onCommand("NEW_BATCH",null)}) { Text("开始新一轮") }
            TextButton({onCommand("CANCEL",null)}) { Text("取消排队") }
            TextButton({onCommand("RETRY",null)}) { Text("重试失败项") }
            TextButton({testing=true},enabled=settings.configured && settings.enabled) { Text("测试连接（可能计费）") }
        }
        Text("数量限制不等于金额上限。请求超时可能已计费，不会自动重发。关闭 AI 仍可使用本地识别与手动调整。",style=MaterialTheme.typography.bodySmall)
    }
    if(testing) AlertDialog(onDismissRequest={testing=false},title={Text("测试视觉接口？")},text={Text("使用内置测试图片发起一次请求，可能计费并占用本轮数量。请先保存配置。")},confirmButton={TextButton({testing=false;onTest()}) {Text("开始测试")}},dismissButton={TextButton({testing=false}) {Text("取消")}})
}
