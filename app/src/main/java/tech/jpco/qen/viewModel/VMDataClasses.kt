package tech.jpco.qen.viewModel

sealed class MetaEvent {
    object CyclePage : MetaEvent()
    object ClearPage : MetaEvent()
    data class NewPage(val ar: Float) : MetaEvent()
    data class SelectPage(val page: Int) : MetaEvent()
    data class CurrentPage(val ar: Float) : MetaEvent()
}

//TODO refactor content and ratio into normal data class implementation?
data class SelectedPage(val current: Int, val total: Int) {
    var content: List<DrawPoint> = listOf()
        private set

    var ratio: Float = 0f
        private set

    constructor(current: Int, total: Int, content: List<DrawPoint>, ratio: Float) : this(current, total) {
        this.content = content
        this.ratio = ratio
    }

    override fun toString(): String =
        "${this::class.simpleName}(current=$current, total=$total, content.size=${content.size}, ratio=$ratio) " +
                "@${System.identityHashCode(this)}"

}

data class DrawPoint(val x: Float, val y: Float, val type: TouchEventType = TouchEventType.TouchMove)

enum class TouchEventType {
    TouchDown {
        override fun toString(): String = "tDown"
    },

    TouchMove {
        override fun toString(): String = "tMove"
    },

    TouchUp {
        override fun toString(): String = "tUp"
    }
}