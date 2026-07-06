max_threads_count = ENV.fetch("RAILS_MAX_THREADS", 5)
min_threads_count = ENV.fetch("RAILS_MIN_THREADS", max_threads_count)
threads min_threads_count, max_threads_count

port ENV.fetch("PORT", 8080)
environment ENV.fetch("RAILS_ENV", "development")

pidfile ENV["PIDFILE"] if ENV["PIDFILE"]
