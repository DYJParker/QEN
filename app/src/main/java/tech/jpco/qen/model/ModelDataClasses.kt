package tech.jpco.qen.model

data class DrawPoint(val x: Float, val y: Float, val type: TouchEventType = TouchEventType.TouchMove)

sealed class TouchEventType {
    object TouchDown : TouchEventType() {
        override fun toString(): String = "tDown"
    }

    object TouchMove : TouchEventType() {
        override fun toString(): String = "tMove"
    }

    object TouchUp : TouchEventType() {
        override fun toString(): String = "tUp"
    }
}

