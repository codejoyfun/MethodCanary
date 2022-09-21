package com.method.canary.core

/**
 * 链表结构
 * 储存方法Buffer数组的下标,主要作用是记录方法的调用位置，方便之后监听到耗时方法输出调用栈
 */
class IndexRecord {

    var index = 0
    var next: IndexRecord? = null
    var isValid = true
    var source: String? = null

    constructor(index: Int) {
        this.index = index
    }

    constructor() {
        this.isValid = false
    }

    //从记录链表中移除这个方法记录
    fun release() {
        isValid = false
        var record = AppMethodBeat.sIndexRecordHead
        var last: IndexRecord? = null
        while (null != record) {
            if (record == this) {//找到要移除的记录，做移除逻辑
                last?.let { lastRecord ->
                    lastRecord.next = record?.next
                } ?: run {
                    AppMethodBeat.sIndexRecordHead = record?.next
                }
                record.next = null
                break
            }
            //跳转到下一个节点，继续做匹配查找
            last = record
            record = record.next
        }
    }

    override fun toString() = "index:$index,\tisValid:$isValid source:$source"

}