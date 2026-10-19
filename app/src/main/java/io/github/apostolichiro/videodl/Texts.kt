package io.github.apostolichiro.videodl

import io.github.apostolichiro.videodl.engine.Fmt
import io.github.apostolichiro.videodl.engine.Links

/** The words shown for a job, shared by the screen and the notification. */
object Texts {
    fun title(job: Job): String = job.title ?: job.files.firstOrNull()?.name ?: Links.host(job.url)

    fun status(job: Job): String = when (job.status) {
        Status.QUEUED -> "기다리는 중"
        Status.PREPARING -> when (job.step) {
            "UPDATING" -> "다운로드 엔진 업데이트 중…"
            "STARTING" -> "준비 중… (처음 한 번은 조금 걸려요)"
            else -> "영상 찾는 중…"
        }
        Status.DOWNLOADING -> buildString {
            if (job.index != null && job.count != null && job.count > 1) append("${job.index}/${job.count} · ")
            append(
                when (job.step) {
                    "AUDIO" -> "소리 받는 중"
                    "VIDEO_ONLY" -> "영상 받는 중"
                    else -> "받는 중"
                }
            )
            job.progress?.let { append(" ${(it * 100).toInt()}%") }
            job.speed?.takeIf { it > 0 }?.let { append(" · " + Fmt.speed(it)) }
            job.etaSec?.takeIf { it > 0 }?.let { append(" · " + Fmt.eta(it)) }
        }
        Status.PROCESSING -> when (job.step) {
            "Merger" -> "영상과 소리를 MP4로 합치는 중…"
            "ExtractAudio" -> "소리만 뽑는 중…"
            "VideoRemuxer", "VideoConvertor" -> "MP4로 바꾸는 중…"
            else -> if (job.step?.startsWith("Fixup") == true) "MP4로 정리하는 중…" else "마무리하는 중…"
        }
        Status.SAVING -> "다운로드 폴더에 저장하는 중…"
        Status.DONE -> buildString {
            val total = job.files.sumOf { it.size }
            if (job.files.size > 1) append("완료 · 파일 ${job.files.size}개 · ${Fmt.size(total)}")
            else append("완료 · ${Fmt.size(total)} · ${job.files.firstOrNull()?.name?.substringAfterLast('.', "")?.uppercase() ?: ""}")
        }
        Status.FAILED -> job.error ?: "못 받았어요."
        Status.CANCELLED -> "취소했어요"
    }
}
