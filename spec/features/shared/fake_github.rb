require 'json'
require 'socket'
require 'uri'

# A minimal fake of the GitHub API: records every request and answers
#
# - GET and POST paths stubbed with `stub_get` / `stub_post` (the query
#   string is ignored),
# - the OAuth authorize page with a redirect back to `redirect_uri` with a code
#   and the given state (the browser signs in to the fake without a form),
# - everything else 201, or the statuses queued with `respond_next`
#   (the statuses API of the status pushes).
#
# Plain TCPServer so the specs need no web server gem; one thread per
# connection because browsers keep idle connections open.
class FakeGithub
  Request = Struct.new(:method, :path, :headers, :body, keyword_init: true)

  attr_reader :port

  def initialize
    @server = TCPServer.new('127.0.0.1', 0)
    @port = @server.addr[1]
    @requests = []
    @responses = []
    @gets = {}
    @posts = {}
    @mutex = Mutex.new
    @thread = Thread.new do
      loop do
        client = @server.accept
        Thread.new(client) { |c| handle(c) }
      end
    end
  end

  def url
    "http://127.0.0.1:#{port}"
  end

  def requests
    @mutex.synchronize { @requests.dup }
  end

  def respond_next(status, body = {})
    @mutex.synchronize { @responses << [status, body] }
  end

  def stub_get(path, body, status: 200)
    @mutex.synchronize { @gets[path] = [status, body] }
  end

  def stub_post(path, body, status: 200)
    @mutex.synchronize { @posts[path] = [status, body] }
  end

  def stop
    @thread.kill
    @server.close
  end

  private

  def handle(client)
    request_line = client.gets
    return unless request_line

    method, path = request_line.split(' ')
    headers = {}
    while (line = client.gets) && line != "\r\n"
      name, value = line.split(':', 2)
      headers[name.strip.downcase] = value.to_s.strip
    end
    raw = client.read(headers['content-length'].to_i)
    body = (JSON.parse(raw) rescue raw)
    uri = URI.parse(path)
    @mutex.synchronize do
      @requests << Request.new(method: method, path: path, headers: headers, body: body)
    end
    if method == 'GET' && uri.path == '/login/oauth/authorize'
      params = URI.decode_www_form(uri.query.to_s).to_h
      location = "#{params['redirect_uri']}?" +
                 URI.encode_www_form(code: 'fake-code', state: params['state'])
      client.write("HTTP/1.1 302 Found\r\nLocation: #{location}\r\n" \
                   "Content-Length: 0\r\nConnection: close\r\n\r\n")
      return
    end
    status, response = @mutex.synchronize do
      if method == 'GET'
        @gets[uri.path] || [404, { message: 'Not Found' }]
      else
        @posts[uri.path] || @responses.shift || [201, { id: @requests.size }]
      end
    end
    payload = response.to_json
    client.write("HTTP/1.1 #{status} X\r\nContent-Type: application/json\r\n" \
                 "Content-Length: #{payload.bytesize}\r\nConnection: close\r\n\r\n#{payload}")
  rescue StandardError => e
    warn "FakeGithub: #{e.message}"
  ensure
    client&.close
  end
end

shared_context 'with fake github' do
  before(:each) { @fake_github = FakeGithub.new }
  after(:each) { @fake_github.stop }

  def github_requests
    @fake_github.requests
  end

  def wait_for_github_request(state, timeout: 15)
    wait_until(timeout) { github_requests.find { |r| r.body.is_a?(Hash) && r.body['state'] == state } }
  end
end
