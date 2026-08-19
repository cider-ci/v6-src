require 'digest'
require 'fileutils'
require 'securerandom'
require 'timeout'

shared_context 'with live executor' do
  let(:project_id)  { 'cider-ci-demo-project' }
  let(:head_commit) { 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec' }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:      project_id,
      name:    'Demo Project',
      git_url: 'local'
    )

    @executor_token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       "e2e-executor-#{SecureRandom.hex(4)}",
      token_hash: Digest::SHA256.hexdigest(@executor_token),
      token_part: @executor_token[0, 8],
      enabled:    true
    )

    log_dir = PROJECT_DIR.join('tmp/executor-logs')
    FileUtils.mkdir_p(log_dir)
    @executor_log_path = log_dir.join("executor-#{SecureRandom.hex(6)}.log").to_s
    @executor_pid = Process.spawn(
      { 'CIDER_CI_EXECUTOR_TOKEN'  => @executor_token,
        'CIDER_CI_SERVER_URL'      => http_base_url,
        'CIDER_CI_EXECUTOR_TRAITS' => 'Bash' },
      PROJECT_DIR.join('bin/executor-run').to_s,
      out: @executor_log_path,
      err: @executor_log_path
    )
    Timeout.timeout(40) do
      sleep 0.5 until File.read(@executor_log_path).include?('Executor sync loop starting')
    end
  end

  after :each do
    Process.kill('TERM', @executor_pid) rescue nil
    Process.wait(@executor_pid)         rescue nil
  end

  # Navigate to the commit's job list and click Run for the named job.
  # Uses exact_text on the <td> to avoid matching job names that are substrings
  # of longer names (e.g. "Exclusive Executor Resource" vs "... with Templated Port").
  def trigger_job(job_name)
    visit "/projects/#{project_id}/commits/#{head_commit}/jobs"
    button = first('td', text: job_name, exact_text: true)
               .ancestor('tr')
               .find('button', text: 'Run')
    scroll_to(button, align: :center)
    button.click
    expect(page).to have_content 'Recorded Jobs'
  end

  # Resolve the detail URL for a job by its key (the <code> element text).
  def job_detail_url(job_key)
    url = find('a code', text: job_key).ancestor('a')[:href]
    url.start_with?('http') ? url : "#{http_base_url}#{url}"
  end

  # Poll the given URL until the page contains a badge with badge_text.
  def wait_for_job_badge(job_url, badge_text, timeout_sec: 120)
    begin
      Timeout.timeout(timeout_sec) do
        until page.has_css?('h3 .badge', text: badge_text, wait: 1)
          sleep 2
          visit job_url
        end
      end
    rescue Timeout::Error
      warn "=== wait_for_job_badge(#{badge_text}) timeout: DB state ==="
      warn database[:jobs].select(:key, :state).all.inspect
      warn database[:tasks].select(:name, :state).all.inspect
      warn database[:trials].select(:state, :error, :dispatched_at).all.inspect
      warn "=== last 60 lines of executor log ==="
      warn File.read(@executor_log_path).lines.last(60).join rescue nil
      raise
    end
  end
end
