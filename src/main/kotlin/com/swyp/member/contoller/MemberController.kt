package com.swyp.member.controller

import com.swyp.member.domain.MemberDto
import com.swyp.member.service.MemberUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.jpa.domain.AbstractPersistable_.id
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/members")
@Tag(name = "Member", description = "회원 관리 API")
class MemberController(
    private val memberUseCase: MemberUseCase
) {

    @Operation(summary = "회원 등록")
    @PostMapping
    fun addMember(@RequestBody dto: MemberDto): ResponseEntity<MemberDto> {
        val saved = memberUseCase.addMember(dto)
        return ResponseEntity.ok(saved)
    }

    @Operation(summary = "회원 정보 수정")
    @PutMapping
    fun updateMember(@RequestBody dto: MemberDto): ResponseEntity<MemberDto> {
        val updated = memberUseCase.updateMember(dto)
        return ResponseEntity.ok(updated)
    }

    @Operation(summary = "회원 조회")
    @GetMapping("/{id}")
    fun findMember(@RequestBody dto : MemberDto): ResponseEntity<MemberDto> {
        val member = memberUseCase.findMember(dto) ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(member)
    }

    @Operation(summary = "회원 삭제")
    @DeleteMapping("/{id}")
    fun deleteMember(@RequestBody dto : MemberDto): ResponseEntity<Void> {
        memberUseCase.deleteMember(dto)
        return ResponseEntity.ok().build()
    }
}