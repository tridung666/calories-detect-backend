package com.tridung.caloriesdetect.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

@Builder
@Getter
@JsonPropertyOrder({
        "data",
        "pageNo",
        "pageSize",
        "totalElements",
        "totalPages",
        "last"
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {
    private List<T> data;
    private int pageNo;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean last;

    public static  <T, R> PageResponse<R> from(Page<T> page, Function<T, R> mapper) {

        return PageResponse.<R>builder()
                .data(page.getContent().stream().map(mapper).toList())
                .pageNo(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();

    }
}
