class ApplicationController < ActionController::API
  before_action :authenticate_optional

  rescue_from StandardError, with: :render_unhandled
  rescue_from Stackverse::ProblemError, with: :render_problem_error
  rescue_from Stackverse::ValidationError, with: :render_validation_error
  rescue_from ActionDispatch::Http::Parameters::ParseError do
    render_problem(400, "Bad Request", "Request validation failed.")
  end
  rescue_from ActiveRecord::RecordNotFound do
    render_problem(404, "Not Found")
  end

  private

  attr_reader :current_caller, :query_params

  def authenticate_optional
    @query_params = Stackverse::QueryParams.new(request.query_string)
    @current_caller = Stackverse::AuthService.authenticate(
      request.authorization,
      @query_params,
      request.headers["Accept-Language"]
    )
  end

  def require_caller!
    Stackverse::Errors.unauthorized unless current_caller
    current_caller
  end

  def require_role!(role)
    caller = require_caller!
    unless caller.roles.include?(role)
      Stackverse::EventLog.info(
        "authz_denied",
        "denied",
        "Denied a request lacking the required role",
        actor: caller.username
      )
      Stackverse::Errors.forbidden("You do not have the role required for this operation.")
    end
    caller
  end

  def body_hash
    request.request_parameters.to_h
  end

  def render_json(payload, status: 200, headers: {})
    response.headers.merge!(headers)
    render body: JSON.generate(payload), status: status, content_type: "application/json"
  end

  def render_etag(payload, extra_headers = {})
    etag, body = Stackverse::Etag.headers_for(payload)
    headers = { "ETag" => etag, "Cache-Control" => "no-cache" }.merge(extra_headers)
    response.headers.merge!(headers)
    return head :not_modified if Stackverse::Etag.matches?(request, etag)

    render body: body, status: 200, content_type: "application/json"
  end

  def render_problem_error(error)
    problem = error.problem
    detail = problem.detail
    if problem.detail_key
      language = Stackverse::MessageCatalog.resolve(query_params.first("lang"), request.headers["Accept-Language"])
      detail = Stackverse::MessageCatalog.localize(problem.detail_key, language)
    end
    render_problem(problem.status, problem.title, detail)
  end

  def render_validation_error(error)
    Stackverse::EventLog.info(
      "input_validation_failed",
      "failure",
      "Request validation failed",
      error_code: "validation_failed",
      fields: error.violations.map(&:field).join(",")
    )
    language = Stackverse::MessageCatalog.resolve(query_params.first("lang"), request.headers["Accept-Language"])
    errors = error.violations.map do |violation|
      {
        field: violation.field,
        messageKey: violation.message_key,
        message: Stackverse::MessageCatalog.localize(violation.message_key, language)
      }
    end
    render_problem(400, "Bad Request", "Request validation failed.", errors: errors)
  end

  def render_unhandled(error)
    if error.is_a?(ActiveRecord::StatementInvalid) || error.is_a?(PG::Error)
      Stackverse::EventLog.error(
        "dependency_call_failed",
        "failure",
        "PostgreSQL call failed during a request",
        dependency: "postgres",
        error_code: error.class.name.demodulize.underscore
      )
    else
      Rails.logger.error(message: "Unhandled error", exception: "#{error.class}: #{error.message}")
    end
    render_problem(500, "Internal Server Error", "An unexpected error occurred.")
  end

  def render_problem(status, title, detail = nil, errors: nil)
    payload = { type: "about:blank", title: title, status: status }
    payload[:detail] = detail if detail
    payload[:errors] = errors if errors
    render body: JSON.generate(payload), status: status, content_type: "application/problem+json"
  end
end
