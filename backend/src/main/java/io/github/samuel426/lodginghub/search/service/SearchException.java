package io.github.samuel426.lodginghub.search.service;

public final class SearchException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final SearchFailure reason;

  public SearchException(SearchFailure failure) {
    super(failure.name());
    this.reason = failure;
  }

  public SearchFailure failure() {
    return reason;
  }
}
