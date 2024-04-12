package com.votalks.api.service;

import static java.util.function.Predicate.*;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.votalks.api.dto.vote.VoteCreateDto;
import com.votalks.api.dto.vote.VoteOptionWithCountDto;
import com.votalks.api.dto.vote.VoteReadDto;
import com.votalks.api.dto.vote.VoteTakeDto;
import com.votalks.api.persistence.entity.Category;
import com.votalks.api.persistence.entity.Uuid;
import com.votalks.api.persistence.entity.UuidVoteOption;
import com.votalks.api.persistence.entity.Vote;
import com.votalks.api.persistence.entity.VoteOption;
import com.votalks.api.persistence.repository.CommentRepository;
import com.votalks.api.persistence.repository.UuidVoteOptionRepository;
import com.votalks.api.persistence.repository.VoteOptionRepository;
import com.votalks.api.persistence.repository.VoteRepository;
import com.votalks.global.error.exception.BadRequestException;
import com.votalks.global.error.exception.NotFoundException;
import com.votalks.global.error.model.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class VoteService {
	private final VoteRepository voteRepository;
	private final VoteOptionRepository voteOptionRepository;
	private final UuidVoteOptionRepository uuidVoteOptionRepository;
	private final CommentRepository commentRepository;
	private final UuidService uuidService;

	public HttpHeaders create(
		VoteCreateDto dto,
		HttpServletRequest request,
		HttpServletResponse response
	) {
		final Uuid uuid = uuidService.getOrCreate(request, response);
		final Vote vote = Vote.create(dto, uuid);
		final List<VoteOption> voteOptions = dto.voteOptions()
			.stream()
			.map(optionValue -> VoteOption.create(optionValue, vote))
			.toList();

		voteRepository.save(vote);
		voteOptionRepository.saveAll(voteOptions);

		return uuidService.getHttpHeaders(uuid);
	}

	//필요
	public HttpHeaders select(
		VoteTakeDto dto,
		Long id,
		HttpServletRequest request,
		HttpServletResponse response
	) {
		final Uuid uuid = uuidService.getOrCreate(request, response);
		final VoteOption voteOption = voteOptionRepository.findByIdAndVote_Id(dto.voteOptionId(), id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.FAIL_NOT_VOTE_OPTION_FOUND));

		validateAlreadyVoted(uuid);
		voteOption.select();

		final UuidVoteOption uuidVoteOption = UuidVoteOption.create(uuid, voteOption);
		uuidVoteOptionRepository.save(uuidVoteOption);

		return uuidService.getHttpHeaders(uuid);
	}

	@Transactional(readOnly = true)
	public VoteReadDto read(Long id) {
		final Vote vote = voteRepository.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.FAIL_NOT_VOTE_FOUND));

		return getReadDto(vote);
	}

	@Transactional(readOnly = true)
	public Page<VoteReadDto> readAll(int page, int size, String category) {
		Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
		Page<Vote> votes = getPagedVotesByCategory(category, pageable);

		return votes.map(this::getReadDto);
	}

	private VoteReadDto getReadDto(Vote vote) {
		final List<VoteOptionWithCountDto> voteOptionsWithCounts = voteOptionRepository.findAllByVote(vote)
			.stream()
			.map(voteOption -> new VoteOptionWithCountDto(voteOption.getId(), voteOption.getContent(),
				voteOption.getVoteCount()))
			.toList();

		final int totalVoteCount = voteOptionsWithCounts.stream()
			.map(VoteOptionWithCountDto::count)
			.mapToInt(Integer::intValue)
			.sum();

		final int totalCommentCount = commentRepository.countByVote(vote);

		return vote.toVoteReadDto(totalVoteCount, voteOptionsWithCounts, totalCommentCount);
	}

	private Page<Vote> getPagedVotesByCategory(String category, Pageable pageable) {
		return Optional.ofNullable(category)
			.filter(not(String::isBlank))
			.filter(Category::contains)
			.map(c -> voteRepository.findAllByCategory(Category.valueOf(category), pageable))
			.orElseGet(() -> voteRepository.findAll(pageable));
	}

	private void validateAlreadyVoted(Uuid uuid) {
		if (uuidVoteOptionRepository.existsByUuid(uuid)) {
			throw new BadRequestException(ErrorCode.FAIL_ALREADY_VOTE);
		}
	}
}
