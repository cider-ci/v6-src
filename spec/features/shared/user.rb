shared_context :signed_in_as_current_user do
  before :each do
    unless @current_user
      raise "current_user not set"
    end
    visit '/'
    set_session_cookie @current_user
    visit '/'
  end
end


shared_context :signed_in_as_an_admin do
  before :each do
    @admin ||= FactoryBot.create :admin
    @current_user = @admin
  end
  include_context :signed_in_as_current_user
end
