module Stackverse
  module Errors
    module_function

    def not_found
      raise ProblemError, AppProblem.new(status: 404, title: "Not Found")
    end

    def unauthorized(detail = "Authentication is required.")
      raise ProblemError, AppProblem.new(status: 401, title: "Unauthorized", detail: detail)
    end

    def forbidden(detail, detail_key: nil)
      raise ProblemError, AppProblem.new(status: 403, title: "Forbidden", detail: detail, detail_key: detail_key)
    end

    def conflict(detail, detail_key: nil)
      raise ProblemError, AppProblem.new(status: 409, title: "Conflict", detail: detail, detail_key: detail_key)
    end

    def bad_request(detail)
      raise ProblemError, AppProblem.new(status: 400, title: "Bad Request", detail: detail)
    end
  end
end
