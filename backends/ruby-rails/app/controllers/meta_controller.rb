class MetaController < ApplicationController
  skip_before_action :authenticate_optional

  def healthz
    head :ok
  end

  def readyz
    started_at = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    Stackverse::Sql.connection.execute("select 1")
    log_readiness(true)
    head :ok
  rescue StandardError => e
    duration_ms = ((Process.clock_gettime(Process::CLOCK_MONOTONIC) - started_at) * 1000).round
    if log_readiness(false)
      Stackverse::EventLog.warn(
        "dependency_call_failed",
        "failure",
        "Readiness lost: database unreachable",
        dependency: "postgres",
        duration_ms: duration_ms,
        error_code: e.class.name.demodulize.underscore
      )
    end
    head :service_unavailable
  end

  private

  def log_readiness(ready)
    previous = self.class.ready
    return false if previous == ready

    self.class.ready = ready
    Rails.logger.info("Readiness restored: database reachable again") if ready
    true
  end

  class << self
    attr_accessor :ready
  end
  self.ready = true
end
