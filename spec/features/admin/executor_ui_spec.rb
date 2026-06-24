require 'spec_helper'

feature 'Admin: Executor management UI' do

  before :each do
    @admin = FactoryBot.create(:admin)
    @user  = FactoryBot.create(:user)
  end

  context 'as an admin' do
    before :each do
      set_session_cookie @admin
      visit '/admin/executors/'
    end

    scenario 'page loads with heading and empty state' do
      expect(page).to have_content 'Admin: Executors'
      expect(page).to have_content 'No executors configured.'
      expect(page).to have_content 'Add executor'
    end

    scenario 'creates an executor and shows the one-time token' do
      fill_in 'Name', with: 'build-box'
      fill_in 'Traits (comma-separated)', with: 'Bash, Ruby'
      fill_in 'Max load', with: '8'
      click_on 'Add executor'

      expect(page).to have_content 'Executor created. Copy this token now'
      expect(page).to have_content 'build-box'
      expect(page).to have_css 'code', text: /.{32,}/

      expect(database[:executors].where(name: 'build-box').count).to eq 1
    end

    scenario 'token alert can be dismissed' do
      fill_in 'Name', with: 'temp-executor'
      fill_in 'Traits (comma-separated)', with: ''
      fill_in 'Max load', with: '4'
      click_on 'Add executor'

      expect(page).to have_content 'Executor created. Copy this token now'
      click_on 'Dismiss'
      expect(page).not_to have_content 'Executor created. Copy this token now'
    end

    scenario 'created executor appears in table with correct details' do
      fill_in 'Name', with: 'detail-check'
      fill_in 'Traits (comma-separated)', with: 'Bash'
      fill_in 'Max load', with: '2'
      click_on 'Add executor'

      within('table') do
        expect(page).to have_content 'detail-check'
        expect(page).to have_content 'Bash'
        expect(page).to have_content '2'
        expect(page).to have_content '✓'
        expect(page).to have_button 'Disable'
      end
    end

    scenario 'can disable and re-enable an executor' do
      fill_in 'Name', with: 'toggle-exec'
      fill_in 'Traits (comma-separated)', with: ''
      fill_in 'Max load', with: '4'
      click_on 'Add executor'

      within('table') do
        click_on 'Disable'
        expect(page).to have_content '✗'
        expect(page).to have_button 'Enable'

        click_on 'Enable'
        expect(page).to have_content '✓'
        expect(page).to have_button 'Disable'
      end

      row = database[:executors].where(name: 'toggle-exec').first
      expect(row[:enabled]).to eq true
    end

    scenario 'can remove an executor' do
      fill_in 'Name', with: 'removable'
      fill_in 'Traits (comma-separated)', with: ''
      fill_in 'Max load', with: '4'
      click_on 'Add executor'

      within('table') do
        click_on 'Remove'
      end

      expect(page).to have_content 'No executors configured.'
      expect(database[:executors].where(name: 'removable').count).to eq 0
    end
  end

  context 'as a non-admin user' do
    before :each do
      set_session_cookie @user
      visit '/admin/executors/'
    end

    scenario 'page returns 403' do
      expect(page).to have_content 'Request ERROR 403'
    end
  end

end
