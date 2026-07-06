module Stackverse
  class ProblemError < StandardError
    attr_reader :problem

    def initialize(problem)
      @problem = problem
      super(problem.detail || problem.title)
    end
  end
end
